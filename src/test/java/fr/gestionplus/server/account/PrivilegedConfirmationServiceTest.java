package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.account.PrivilegedConfirmationProofStore.Proof;
import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AccountMfaService.Verification;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserCredentialLookup;

class PrivilegedConfirmationServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
	private static final String PREVIEW = "a".repeat(64);
	private static final String PASSWORD = "Fictitious-Privileged-Password-2026!";
	private final PrivilegedConfirmationProofStore proofStore = mock(PrivilegedConfirmationProofStore.class);
	private final AccountStore accountStore = mock(AccountStore.class);
	private final AuthenticatedUserProvider authenticated = mock(AuthenticatedUserProvider.class);
	private final UserCredentialLookup credentials = mock(UserCredentialLookup.class);
	private final CredentialService passwords = mock(CredentialService.class);
	private final AccountMfaService mfa = mock(AccountMfaService.class);
	private final UserAccount account = mock(UserAccount.class);
	private final UserCredential credential = mock(UserCredential.class);
	private final UUID userID = UUID.randomUUID();
	private final PrivilegedConfirmationService service = new PrivilegedConfirmationService(
			new StaticListableBeanFactory().getBeanProvider(PrivilegedConfirmationVerifier.class), proofStore,
			accountStore, authenticated, credentials, passwords, mfa, Clock.fixed(NOW, ZoneOffset.UTC));

	@BeforeEach
	void prepareCurrentAccountWithSuccessfulPasswordAndMfaVerification() {
		when(account.getUserID()).thenReturn(userID);
		when(account.getSessionVersion()).thenReturn(7L);
		when(account.getStatu()).thenReturn(UserAccountStatus.ACTIVE);
		when(authenticated.getCurrentAccount()).thenReturn(account);
		when(accountStore.findByIdForUpdate(userID)).thenReturn(Optional.of(account));
		when(credential.getUserId()).thenReturn(userID);
		when(credentials.findByUserId(userID)).thenReturn(Optional.of(credential));
		when(passwords.verifyPassword(credential, PASSWORD)).thenReturn(true);
		when(mfa.verifyForLogin(account, "000042")).thenReturn(Verification.VERIFIED);
	}

	@ParameterizedTest
	@EnumSource(Action.class)
	void issuesAnOpaqueTokenAndStoresOnlyItsHashWithTheExactActionAndFiveMinuteDeadline(Action action) {
		String token = service.issueProof(action, PREVIEW, PASSWORD, "000042");
		ArgumentCaptor<Proof> proof = ArgumentCaptor.forClass(Proof.class);
		verify(proofStore).replaceCurrent(proof.capture());
		assertTrue(token.matches("[A-Za-z0-9_-]{43}"));
		assertEquals(PrivilegedConfirmationService.digest(token), proof.getValue().proofHash());
		assertFalse(token.equals(proof.getValue().proofHash()));
		assertEquals(userID, proof.getValue().userID());
		assertEquals(7, proof.getValue().sessionVersion());
		assertEquals(action, proof.getValue().action());
		assertEquals(PREVIEW, proof.getValue().previewDigest());
		assertEquals(NOW.plusSeconds(300), proof.getValue().expiresAt());
	}

	@Test
	void aWrongPasswordNeverRequestsMfaOrCreatesAProof() {
		when(passwords.verifyPassword(credential, PASSWORD)).thenReturn(false);
		assertThrows(AccessDeniedException.class, () -> service.issueProof(Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, PASSWORD, "000042"));
		verifyNoInteractions(proofStore, mfa);
	}

	@ParameterizedTest
	@EnumSource(value = Verification.class, names = { "NOT_REQUIRED", "DENIED" })
	void onlyAnActuallyVerifiedTotpCanProduceAProof(Verification result) {
		when(mfa.verifyForLogin(account, "000042")).thenReturn(result);
		assertThrows(AccessDeniedException.class, () -> service.issueProof(Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, PASSWORD, "000042"));
		verifyNoInteractions(proofStore);
	}

	@Test
	void malformedActionsAndSummariesAreRefusedBeforeAccountOrPasswordLookup() {
		assertThrows(AccessDeniedException.class, () -> service.issueProof(null, PREVIEW, PASSWORD, "000042"));
		assertThrows(AccessDeniedException.class, () -> service.issueProof(Action.STRUCTURE_ADMIN_TRANSFER, "invalid", PASSWORD, "000042"));
		verifyNoInteractions(accountStore, passwords, proofStore);
	}

	@Test
	void aStaleAccountSessionCannotProduceAProof() {
		UserAccount stale = mock(UserAccount.class);
		when(stale.getUserID()).thenReturn(userID);
		when(stale.getSessionVersion()).thenReturn(6L);
		when(authenticated.getCurrentAccount()).thenReturn(stale);
		assertThrows(AccessDeniedException.class, () -> service.issueProof(Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, PASSWORD, "000042"));
		verifyNoInteractions(passwords, proofStore);
	}
}
