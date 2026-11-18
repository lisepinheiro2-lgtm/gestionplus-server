package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.TestPropertySource;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;

@TestPropertySource(properties = "gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class AccountActivationSmsPostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-18T12:00:00Z");
	private static final String PASSWORD = "valid-test-password";

	@Autowired
	private InvitationTokenIssuer tokenIssuer;
	@Autowired
	private ActivationCodeDigester codeDigester;
	@Autowired
	private ObjectProvider<Clock> clocks;

	private UUID userID;
	private Invitation invitation;
	private String token;
	private String code;
	private ActivationIdentityChallenge challenge;

	@BeforeEach
	void requestCode() {
		when(clock.instant()).thenReturn(NOW);
		HmacActivationIdentityVerifier hmacVerifier = new HmacActivationIdentityVerifier(challengeStore, codeDigester,
				clocks);
		when(verifier.verify(any(), any(), anyString(), anyString())).thenAnswer(call -> hmacVerifier.verify(
				call.getArgument(0), call.getArgument(1), call.getArgument(2), call.getArgument(3)));

		userID = preparePendingAccount();
		UUID invitationID = UUID.randomUUID();
		token = tokenIssuer.token(userID, invitationID);
		invitation = new Invitation(invitationID, userID, structureID, InvitationTokenIssuer.digest(token),
				NOW.plusSeconds(3600), null);
		invitationStore.save(invitation);
		String phoneNumber = account(userID).getPhoneNumber();
		when(credentialService.encodePassword(userID, PASSWORD)).thenReturn(new UserCredential(userID, "new-hash"));
		SecurityContextHolder.clearContext();

		activation.requestActivationChallenge(invitationID, token);

		ArgumentCaptor<String> sentCode = ArgumentCaptor.forClass(String.class);
		verify(messageSender).send(eq(phoneNumber), sentCode.capture());
		code = sentCode.getValue();
		challenge = currentChallenge().orElseThrow();
		assertNull(challenge.consumedAt());
	}

	@Test
	void aCorrectCodeActivatesTheAccountAndConsumesTheChallengeOnce() {
		completeActivation(code);

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertTrue(credentials.existsById(userID));
		Invitation completed = invitationStore.findByID(invitation.invitationID()).orElseThrow();
		assertEquals(NOW, completed.completedAt());
		assertNull(completed.tokenDigest());
		assertTrue(currentChallenge().isEmpty());
		assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject("""
				SELECT consumed_at IS NOT NULL AND revoked_at IS NULL
				FROM account_activation_identity_challenges WHERE challenge_id = ?
				""", Boolean.class, challenge.challengeID()));

		assertThrows(AccessDeniedException.class, () -> completeActivation(code));
		assertEquals(1, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
	}

	@Test
	void threeIncorrectCodesRevokeTheChallengeAndPreventActivationWithTheCorrectCode() {
		String wrongCode = code.equals("000000") ? "000001" : "000000";

		for (int attempt = 1; attempt <= 2; attempt++) {
			assertThrows(AccessDeniedException.class, () -> completeActivation(wrongCode));
			ActivationIdentityChallenge current = currentChallenge().orElseThrow();
			assertEquals(challenge.challengeID(), current.challengeID());
			assertEquals(attempt, current.failedAttempts());
			assertNull(current.consumedAt());
		}

		assertThrows(AccessDeniedException.class, () -> completeActivation(wrongCode));
		assertTrue(currentChallenge().isEmpty());
		assertThrows(AccessDeniedException.class, () -> completeActivation(code));

		assertEquals(Boolean.TRUE, jdbcTemplate.queryForObject("""
				SELECT failed_attempts = 3 AND revoked_at IS NOT NULL AND consumed_at IS NULL
				FROM account_activation_identity_challenges WHERE challenge_id = ?
				""", Boolean.class, challenge.challengeID()));
		assertPendingActivation();
	}

	@Test
	void aChallengeThatExpiresBeforeConsumptionCannotActivateTheAccount() {
		when(credentialService.encodePassword(userID, PASSWORD)).thenAnswer(call -> {
			when(clock.instant()).thenReturn(challenge.expiresAt());
			return new UserCredential(userID, "new-hash");
		});

		assertThrows(AccessDeniedException.class, () -> completeActivation(code));

		verify(credentialService).encodePassword(userID, PASSWORD);
		assertEquals(challenge, currentChallenge().orElseThrow());
		assertPendingActivation();
	}

	@Test
	void aRolledBackActivationLeavesTheSameCodeUsable() {
		UserAccount other = createAccount(UserAccountStatus.ACTIVE, Set.of(), null);

		assertThrows(AccountActivationConflictException.class, () -> activation.completeActivation(
				invitation.invitationID(), token, code, other.getUserName(), PASSWORD));

		assertEquals(challenge, currentChallenge().orElseThrow());
		assertPendingActivation();

		completeActivation(code);

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertTrue(currentChallenge().isEmpty());
	}

	private Optional<ActivationIdentityChallenge> currentChallenge() {
		return challengeStore.findCurrentByInvitationAndUser(invitation.invitationID(), userID);
	}

	private void completeActivation(String proof) {
		activation.completeActivation(invitation.invitationID(), token, proof, "activated_" + userID, PASSWORD);
	}

	private void assertPendingActivation() {
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertEquals(invitation, invitationStore.findByID(invitation.invitationID()).orElseThrow());
		assertFalse(credentials.existsById(userID));
	}
}
