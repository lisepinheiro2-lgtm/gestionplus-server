package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import org.springframework.transaction.PlatformTransactionManager;

class PasswordChangeServiceTest {

	private final AuthenticatedUserProvider authenticated = mock(AuthenticatedUserProvider.class);
	private final AccountStore accounts = mock(AccountStore.class);
	private final UserCredentialLookup credentials = mock(UserCredentialLookup.class);
	private final CredentialService passwords = mock(CredentialService.class);
	private final AccountMfaService mfa = mock(AccountMfaService.class);
	private final SessionRevocationService sessions = mock(SessionRevocationService.class);
	private final AuditService audit = mock(AuditService.class);
	private final PasswordChangeService service = new PasswordChangeService(authenticated, accounts, credentials,
			passwords, mfa, sessions, audit, mock(PlatformTransactionManager.class));
	private final UUID userID = UUID.randomUUID();
	private final UserAccount current = account(userID, UserAccountStatus.ACTIVE, 3);
	private final UserCredential credential = new UserCredential(userID, "old-test-hash");
	private final UserCredential replacement = new UserCredential(userID, "new-test-hash");

	@BeforeEach
	void prepare() {
		when(authenticated.getCurrentAccount()).thenReturn(current);
		when(accounts.findByIdForUpdate(userID)).thenReturn(Optional.of(current));
		when(credentials.findByUserId(userID)).thenReturn(Optional.of(credential));
		when(passwords.verifyPassword(credential, "current-private-password")).thenReturn(true);
		when(passwords.encodePassword(userID, "new-private-password")).thenReturn(replacement);
		when(mfa.verifyForLogin(current, "012345")).thenReturn(AccountMfaService.Verification.NOT_REQUIRED);
	}

	@ParameterizedTest
	@EnumSource(value = AccountMfaService.Verification.class, names = { "NOT_REQUIRED", "VERIFIED" })
	void changesTheSecretOnlyAfterPasswordAndMfaVerification(AccountMfaService.Verification result) {
		when(mfa.verifyForLogin(current, "012345")).thenReturn(result);

		service.changePassword("current-private-password", "new-private-password", "012345");

		var order = inOrder(accounts, passwords, mfa, audit, sessions);
		order.verify(passwords).validatePassword("new-private-password");
		order.verify(accounts).findByIdForUpdate(userID);
		order.verify(passwords).verifyPassword(credential, "current-private-password");
		order.verify(mfa).verifyForLogin(current, "012345");
		order.verify(passwords).encodePassword(userID, "new-private-password");
		order.verify(accounts).replaceCredential(replacement);
		order.verify(accounts).advanceSessionVersion(userID);
		order.verify(audit).record(eq(AuditAction.ACCOUNT_PASSWORD_CHANGE),
				eq(AuditResult.SUCCESS), any());
		order.verify(audit).record(eq(AuditAction.AUTH_SESSION_REVOKE),
				eq(AuditResult.SUCCESS), any());
		order.verify(sessions).revokeAllSessionsAfterCommit(current);
	}

	@Test
	void requiresAnAuthenticatedIdentity() {
		when(authenticated.getCurrentAccount()).thenThrow(
				new AuthenticationCredentialsNotFoundException("Authentification requise."));

		assertThrows(AuthenticationCredentialsNotFoundException.class,
				() -> service.changePassword("current-private-password", "new-private-password", "012345"));
		verifyNoInteractions(accounts, credentials, passwords, mfa, sessions, audit);
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = { "DISABLED", "PENDING_ACTIVATION" })
	void cannotReactivateAnInactiveAccount(UserAccountStatus status) {
		when(accounts.findByIdForUpdate(userID)).thenReturn(Optional.of(account(userID, status, 3)));
		assertDenied();
		verifyNoInteractions(credentials, mfa);
		verify(passwords, never()).encodePassword(any(), any());
	}

	@Test
	void refusesMissingForeignAndStaleAccounts() {
		when(accounts.findByIdForUpdate(userID)).thenReturn(Optional.empty());
		assertDenied();
		when(accounts.findByIdForUpdate(userID)).thenReturn(Optional.of(account(UUID.randomUUID(),
				UserAccountStatus.ACTIVE, 3)));
		assertDenied();
		when(accounts.findByIdForUpdate(userID)).thenReturn(Optional.of(account(userID, UserAccountStatus.ACTIVE, 4)));
		assertDenied();
		verifyNoInteractions(credentials, mfa);
		verify(passwords, never()).encodePassword(any(), any());
	}

	@Test
	void refusesMissingOrMismatchedCredentials() {
		when(credentials.findByUserId(userID)).thenReturn(Optional.empty());
		assertDenied();
		when(credentials.findByUserId(userID)).thenReturn(
				Optional.of(new UserCredential(UUID.randomUUID(), "foreign-test-hash")));
		assertDenied();
		verifyNoInteractions(mfa);
		verify(passwords, never()).encodePassword(any(), any());
	}

	@Test
	void refusesWrongPasswordsAndRejectedMfa() {
		when(passwords.verifyPassword(credential, "current-private-password")).thenReturn(false);
		assertDenied();
		verifyNoInteractions(mfa);
		when(passwords.verifyPassword(credential, "current-private-password")).thenReturn(true);
		when(mfa.verifyForLogin(current, "012345")).thenReturn(AccountMfaService.Verification.DENIED);
		assertDenied();
		when(mfa.verifyForLogin(current, "012345")).thenReturn(null);
		assertDenied();
	}

	@Test
	void aRejectedNewPasswordCannotLockTheAccountOrChangeTheCredential() {
		doThrow(new IllegalArgumentException("Mot de passe refusé.")).when(passwords)
				.validatePassword("new-private-password");

		assertThrows(IllegalArgumentException.class,
				() -> service.changePassword("current-private-password", "new-private-password", "012345"));
		verifyNoInteractions(accounts, mfa, sessions, audit);
	}

	@Test
	void aStorageFailureCannotReportSuccessOrScheduleSessionRevocation() {
		doThrow(new IllegalStateException("Écriture indisponible.")).when(accounts).replaceCredential(replacement);

		assertThrows(IllegalStateException.class,
				() -> service.changePassword("current-private-password", "new-private-password", "012345"));
		verify(accounts, never()).advanceSessionVersion(any());
		verifyNoInteractions(sessions, audit);
	}

	private void assertDenied() {
		assertThrows(AccessDeniedException.class,
				() -> service.changePassword("current-private-password", "new-private-password", "012345"));
		verify(accounts, never()).replaceCredential(any());
		verify(accounts, never()).advanceSessionVersion(any());
		verifyNoInteractions(sessions, audit);
	}

	private UserAccount account(UUID id, UserAccountStatus status, long version) {
		return new UserAccount(id, "test_" + id, status, Set.of(), UUID.randomUUID(), "Exemple", "Test",
				"test@example.invalid", "0600000000", version);
	}
}
