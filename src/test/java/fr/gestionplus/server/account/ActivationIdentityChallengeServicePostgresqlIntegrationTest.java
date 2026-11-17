package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;

@TestPropertySource(properties = "gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class ActivationIdentityChallengeServicePostgresqlIntegrationTest
		extends AbstractAccountInvitationPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");

	@Autowired
	private ActivationIdentityChallengeService challengeService;
	@Autowired
	private ActivationCodeDigester codeDigester;

	@BeforeEach
	void controlTime() {
		when(clock.instant()).thenReturn(NOW);
	}

	@Test
	void commitsTheDigestBeforeSendingThePlainCodeOutsideATransaction() {
		UUID userID = preparePendingAccount();
		UUID invitationID = prepareInvitation(userID);
		String phone = account(userID).getPhoneNumber();

		doAnswer(invocation -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			assertTrue(challengeStore.findCurrentByInvitationAndUser(invitationID, userID).isPresent());
			return null;
		}).when(messageSender).send(eq(phone), anyString());

		challengeService.createChallenge(invitationID);

		ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
		verify(messageSender).send(eq(phone), code.capture());
		ActivationIdentityChallenge challenge = challengeStore.findCurrentByInvitationAndUser(invitationID, userID)
				.orElseThrow();

		assertTrue(code.getValue().matches("[0-9]{6}"));
		assertEquals(codeDigester.digest(challenge.challengeID(), code.getValue()), challenge.codeDigest());
		assertNotEquals(code.getValue(), challenge.codeDigest());
		assertEquals(phone, challenge.phoneNumber());
		assertEquals(NOW, challenge.createdAt());
		assertEquals(NOW.plusSeconds(300), challenge.expiresAt());
		assertEquals(1, challengeStore.countByInvitationAndUser(invitationID, userID));
	}

	@Test
	void refusesAResendAtFiftyNineSecondsAndAllowsItAtSixty() {
		UUID userID = preparePendingAccount();
		UUID invitationID = prepareInvitation(userID);
		challengeService.createChallenge(invitationID);
		UUID previousID = challengeStore.findCurrentByInvitationAndUser(invitationID, userID)
				.orElseThrow().challengeID();

		when(clock.instant()).thenReturn(NOW.plusSeconds(59));
		assertThrows(AccountActivationConflictException.class, () -> challengeService.createChallenge(invitationID));
		assertEquals(1, challengeStore.countByInvitationAndUser(invitationID, userID));

		when(clock.instant()).thenReturn(NOW.plusSeconds(60));
		challengeService.createChallenge(invitationID);

		ActivationIdentityChallenge replacement = challengeStore.findCurrentByInvitationAndUser(invitationID, userID)
				.orElseThrow();
		assertNotEquals(previousID, replacement.challengeID());
		assertEquals(NOW.plusSeconds(60), replacement.createdAt());
		assertEquals(2, challengeStore.countByInvitationAndUser(invitationID, userID));
		verify(messageSender, times(2)).send(anyString(), anyString());
	}

	@Test
	void refusesTheFourthSequentialAttemptWhileKeepingAllThreeRows() {
		UUID userID = preparePendingAccount();
		UUID invitationID = prepareInvitation(userID);

		for (int attempt = 0; attempt < 3; attempt++) {
			when(clock.instant()).thenReturn(NOW.plusSeconds(60L * attempt));
			challengeService.createChallenge(invitationID);
		}

		when(clock.instant()).thenReturn(NOW.plusSeconds(180));
		assertThrows(AccountActivationConflictException.class, () -> challengeService.createChallenge(invitationID));
		assertEquals(3, challengeStore.countByInvitationAndUser(invitationID, userID));
		verify(messageSender, times(3)).send(anyString(), anyString());
	}

	@Test
	void compensatesFailedSendsWithoutRestoringTheAttemptQuota() {
		UUID userID = preparePendingAccount();
		UUID invitationID = prepareInvitation(userID);
		IllegalStateException failure = new IllegalStateException("Envoi SMS indisponible.");
		doThrow(failure).when(messageSender).send(anyString(), anyString());

		for (int attempt = 1; attempt <= 3; attempt++) {
			assertSame(failure, assertThrows(IllegalStateException.class,
					() -> challengeService.createChallenge(invitationID)));
			assertTrue(challengeStore.findCurrentByInvitationAndUser(invitationID, userID).isEmpty());
			assertEquals(attempt, challengeStore.countByInvitationAndUser(invitationID, userID));
		}

		assertThrows(AccountActivationConflictException.class, () -> challengeService.createChallenge(invitationID));
		Integer revoked = jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM account_activation_identity_challenges
				WHERE invitation_id = ? AND user_id = ? AND revoked_at IS NOT NULL
				""", Integer.class, invitationID, userID);
		assertNotNull(revoked);
		assertEquals(3, revoked.intValue());
		verify(messageSender, times(3)).send(anyString(), anyString());
	}

	private UUID prepareInvitation(UUID userID) {
		UUID invitationID = UUID.randomUUID();
		invitationStore.save(new Invitation(invitationID, userID, structureID, "test-" + invitationID,
				NOW.plusSeconds(3600), null));
		return invitationID;
	}
}
