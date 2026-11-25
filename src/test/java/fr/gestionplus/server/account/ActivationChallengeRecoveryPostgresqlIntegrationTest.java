package fr.gestionplus.server.account;

import static fr.gestionplus.server.account.SmsIdentityChallenge.Purpose.ACCOUNT_ACTIVATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;

@TestPropertySource(properties = "gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=")
class ActivationChallengeRecoveryPostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
	@Autowired
	private ActivationIdentityChallengeService service;
	@MockitoSpyBean
	private SmsIdentityChallengeStore recoveryStore;

	@BeforeEach
	void controlTime() {
		when(clock.instant()).thenReturn(NOW);
	}

	@Test
	void anUnconfirmedCodeCannotBeConsumedEvenIfItsDigestMatches() {
		SmsIdentityChallenge challenge = pendingChallenge();
		assertFalse(challenge.isUsable(NOW));
		boolean consumed = Boolean.TRUE.equals(new TransactionTemplate(transactionManager)
				.execute(status -> challengeStore.consumeIfCurrent(ACCOUNT_ACTIVATION, challenge.challengeID(), NOW)));
		assertFalse(consumed);
		assertTrue(challengeStore.confirmSend(ACCOUNT_ACTIVATION, challenge.challengeID(), NOW.plusSeconds(1)));
		assertFalse(challengeStore.confirmSend(ACCOUNT_ACTIVATION, challenge.challengeID(), NOW.plusSeconds(2)));
		assertTrue(challengeStore.findCurrentByInvitationAndUser(challenge.binding().invitationID(), challenge.binding().userID())
				.orElseThrow().isUsable(NOW.plusSeconds(1)));
	}

	@Test
	void recoveryKeepsHistoryAndCannotTouchAConfirmedSend() {
		SmsIdentityChallenge pending = pendingChallenge();
		SmsIdentityChallenge confirmed = pendingChallenge();
		assertTrue(challengeStore.confirmSend(ACCOUNT_ACTIVATION, confirmed.challengeID(), NOW));
		assertEquals(0, challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(119)));
		assertEquals(1, challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(120)));
		assertEquals(0, challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(121)));
		assertTrue(challengeStore.findCurrentByInvitationAndUser(pending.binding().invitationID(), pending.binding().userID()).isEmpty());
		assertEquals(1, challengeStore.countByInvitationAndUser(pending.binding().invitationID(), pending.binding().userID()));
		assertTrue(challengeStore.findCurrentByInvitationAndUser(confirmed.binding().invitationID(), confirmed.binding().userID())
				.isPresent());
		assertFalse(challengeStore.confirmSend(ACCOUNT_ACTIVATION, pending.challengeID(), NOW.plusSeconds(121)));
	}

	@Test
	void twoRecoveryWorkersRevokeEachDueChallengeOnlyOnce() throws Exception {
		pendingChallenge();

		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(120)));
			var second = workers.submit(() -> challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(120)));
			assertEquals(1, first.get() + second.get());
		}

	}

	@Test
	void aFailedSendAndFailedImmediateRevocationRemainRecoverableFromTheDatabase() {
		UUID userID = preparePendingAccount();
		UUID invitationID = UUID.randomUUID();
		String digest = InvitationTokenIssuer.digest("a".repeat(43));
		invitationStore.save(new Invitation(invitationID, userID, structureID, digest, NOW.plusSeconds(3600), null));
		IllegalStateException sendingFailure = new IllegalStateException("Envoi indisponible.");
		IllegalStateException revocationFailure = new IllegalStateException("Révocation indisponible.");
		doThrow(sendingFailure).when(messageSender).send(eq(ACCOUNT_ACTIVATION), anyString(), anyString());
		doThrow(revocationFailure).when(recoveryStore).revokeCurrent(eq(ACCOUNT_ACTIVATION), any(), any());

		assertSame(sendingFailure, assertThrows(IllegalStateException.class,
				() -> service.createChallenge(invitationID, digest)));
		assertSame(revocationFailure, sendingFailure.getSuppressed()[0].getCause());
		assertFalse(challengeStore.findCurrentByInvitationAndUser(invitationID, userID).orElseThrow().isUsable(NOW));
		assertEquals(1, challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(120)));
		assertTrue(challengeStore.findCurrentByInvitationAndUser(invitationID, userID).isEmpty());
		assertEquals(1, challengeStore.countByInvitationAndUser(invitationID, userID));
	}

	@Test
	void confirmationIsRefusedAtTheRecoveryDeadline() {
		SmsIdentityChallenge challenge = pendingChallenge();
		assertFalse(challengeStore.confirmSend(ACCOUNT_ACTIVATION, challenge.challengeID(), NOW.plusSeconds(120)));
		assertEquals(1, challengeStore.revokeUnconfirmedSends(NOW.plusSeconds(120)));
	}

	private SmsIdentityChallenge pendingChallenge() {
		UUID userID = preparePendingAccount();
		SmsIdentityChallenge challenge = SmsIdentityChallenge.forActivation(UUID.randomUUID(), userID,
				account(userID).getPhoneNumber(), UUID.randomUUID(), "a".repeat(64), 0, NOW, NOW.plusSeconds(300),
				null, null, null, NOW.plusSeconds(120));
		challengeStore.replaceCurrent(challenge, NOW);
		return challenge;
	}
}
