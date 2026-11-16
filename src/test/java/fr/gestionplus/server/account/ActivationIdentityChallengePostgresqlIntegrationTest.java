
package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;

class ActivationIdentityChallengePostgresqlIntegrationTest
		extends AbstractAccountInvitationPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-15T12:00:00Z");

	@Autowired
	private ActivationIdentityChallengeStore challengeStore;

	@Test
	void savesAndFindsCurrentChallenge() {
		UUID invitationID = UUID.randomUUID();
		ActivationIdentityChallenge challenge = newChallenge(invitationID);

		challengeStore.replaceCurrent(challenge, NOW);

		ActivationIdentityChallenge saved = challengeStore
				.findCurrentByInvitationAndUser(invitationID, actor.getUserID())
				.orElseThrow();

		assertEquals(challenge, saved);
		assertTrue(saved.isUsable(NOW));
	}

	@Test
	void replacingCurrentChallengeRevokesPreviousAndKeepsHistory() {
		UUID invitationID = UUID.randomUUID();
		ActivationIdentityChallenge previous = newChallenge(invitationID);
		ActivationIdentityChallenge replacement = newChallenge(invitationID);

		challengeStore.replaceCurrent(previous, NOW);

		Instant replacedAt = NOW.plusSeconds(10);
		challengeStore.replaceCurrent(replacement, replacedAt);

		assertEquals(replacement, challengeStore
				.findCurrentByInvitationAndUser(invitationID, actor.getUserID())
				.orElseThrow());

		int revokedRows = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM account_activation_identity_challenges
				WHERE challenge_id = ?
				  AND revoked_at = ?
				""", Integer.class,
				previous.challengeID(),
				replacedAt.atOffset(ZoneOffset.UTC));

		assertEquals(1, revokedRows);
		assertEquals(0, challengeStore.recordFailedAttempt(previous.challengeID(), replacedAt));
	}

	@Test
	void replacingOneInvitationDoesNotRevokeAnother() {
		UUID firstInvitationID = UUID.randomUUID();
		UUID secondInvitationID = UUID.randomUUID();

		ActivationIdentityChallenge first = newChallenge(firstInvitationID);
		ActivationIdentityChallenge second = newChallenge(secondInvitationID);
		ActivationIdentityChallenge replacement = newChallenge(firstInvitationID);

		challengeStore.replaceCurrent(first, NOW);
		challengeStore.replaceCurrent(second, NOW);
		challengeStore.replaceCurrent(replacement, NOW.plusSeconds(10));

		assertEquals(replacement, challengeStore
				.findCurrentByInvitationAndUser(firstInvitationID, actor.getUserID())
				.orElseThrow());

		assertEquals(second, challengeStore
				.findCurrentByInvitationAndUser(secondInvitationID, actor.getUserID())
				.orElseThrow());
	}

	@Test
	void thirdFailedAttemptRevokesChallenge() {
		UUID invitationID = UUID.randomUUID();
		ActivationIdentityChallenge challenge = newChallenge(invitationID);

		challengeStore.replaceCurrent(challenge, NOW);

		assertEquals(1, challengeStore.recordFailedAttempt(
				challenge.challengeID(), NOW.plusSeconds(1)));

		assertEquals(2, challengeStore.recordFailedAttempt(
				challenge.challengeID(), NOW.plusSeconds(2)));

		assertEquals(3, challengeStore.recordFailedAttempt(
				challenge.challengeID(), NOW.plusSeconds(3)));

		assertEquals(0, challengeStore.recordFailedAttempt(
				challenge.challengeID(), NOW.plusSeconds(4)));

		assertTrue(challengeStore
				.findCurrentByInvitationAndUser(invitationID, actor.getUserID())
				.isEmpty());

		int failedAttempts = jdbcTemplate.queryForObject("""
				SELECT failed_attempts
				FROM account_activation_identity_challenges
				WHERE challenge_id = ?
				""", Integer.class, challenge.challengeID());

		assertEquals(3, failedAttempts);

		Boolean revoked = jdbcTemplate.queryForObject("""
				SELECT revoked_at IS NOT NULL
				FROM account_activation_identity_challenges
				WHERE challenge_id = ?
				""", Boolean.class, challenge.challengeID());

		assertTrue(Boolean.TRUE.equals(revoked));
	}

	@Test
	void expiredChallengeCannotRecordAnotherFailure() {
		ActivationIdentityChallenge challenge = newChallenge(UUID.randomUUID());

		challengeStore.replaceCurrent(challenge, NOW);

		int result = challengeStore.recordFailedAttempt(
				challenge.challengeID(), challenge.expiresAt());

		assertEquals(0, result);

		int failedAttempts = jdbcTemplate.queryForObject("""
				SELECT failed_attempts
				FROM account_activation_identity_challenges
				WHERE challenge_id = ?
				""", Integer.class, challenge.challengeID());

		assertEquals(0, failedAttempts);
	}

	@Test
	void consumingCurrentChallengeSucceedsOnlyOnce() {
		UUID invitationID = UUID.randomUUID();
		ActivationIdentityChallenge challenge = newChallenge(invitationID);

		challengeStore.replaceCurrent(challenge, NOW);

		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		Boolean firstConsumption = transaction
				.execute(status -> challengeStore.consumeIfCurrent(challenge.challengeID(), NOW.plusSeconds(1)));

		Boolean secondConsumption = transaction
				.execute(status -> challengeStore.consumeIfCurrent(challenge.challengeID(), NOW.plusSeconds(2)));

		assertEquals(Boolean.TRUE, firstConsumption);
		assertEquals(Boolean.FALSE, secondConsumption);

		assertTrue(challengeStore
				.findCurrentByInvitationAndUser(invitationID, actor.getUserID())
				.isEmpty());
	}

	@Test
	void consumptionIsRolledBackWithBusinessTransaction() {
		UUID invitationID = UUID.randomUUID();
		ActivationIdentityChallenge challenge = newChallenge(invitationID);

		challengeStore.replaceCurrent(challenge, NOW);

		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		transaction.executeWithoutResult(status -> {
			assertTrue(challengeStore.consumeIfCurrent(
					challenge.challengeID(), NOW.plusSeconds(1)));

			status.setRollbackOnly();
		});

		assertEquals(challenge, challengeStore
				.findCurrentByInvitationAndUser(invitationID, actor.getUserID())
				.orElseThrow());
	}

	@Test
	void consumptionRequiresAnExistingTransaction() {
		ActivationIdentityChallenge challenge = newChallenge(UUID.randomUUID());

		challengeStore.replaceCurrent(challenge, NOW);

		assertThrows(IllegalTransactionStateException.class,
				() -> challengeStore.consumeIfCurrent(challenge.challengeID(), NOW));
	}

	@Test
	void expiredChallengeCannotBeConsumed() {
		UUID invitationID = UUID.randomUUID();
		ActivationIdentityChallenge challenge = newChallenge(invitationID);

		challengeStore.replaceCurrent(challenge, NOW);

		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		Boolean consumed = transaction.execute(status -> challengeStore.consumeIfCurrent(
				challenge.challengeID(), challenge.expiresAt()));

		assertFalse(Boolean.TRUE.equals(consumed));

		assertTrue(challengeStore
				.findCurrentByInvitationAndUser(invitationID, actor.getUserID())
				.isPresent());
	}

	@Test
	void failedReplacementRollsBackRevocation() {
		UUID invitationID = UUID.randomUUID();
		ActivationIdentityChallenge original = newChallenge(invitationID);

		challengeStore.replaceCurrent(original, NOW);

		ActivationIdentityChallenge duplicate = new ActivationIdentityChallenge(
				original.challengeID(),
				original.userID(),
				original.phoneNumber(),
				original.invitationID(),
				original.codeDigest(),
				0,
				original.createdAt(),
				original.expiresAt(),
				null,
				null);

		assertThrows(DataIntegrityViolationException.class,
				() -> challengeStore.replaceCurrent(
						duplicate, NOW.plusSeconds(1)));

		assertEquals(original, challengeStore
				.findCurrentByInvitationAndUser(invitationID, actor.getUserID())
				.orElseThrow());
	}

	private ActivationIdentityChallenge newChallenge(UUID invitationID) {
		return new ActivationIdentityChallenge(
				UUID.randomUUID(),
				actor.getUserID(),
				"0600000000",
				invitationID,
				"a".repeat(64),
				0,
				NOW,
				NOW.plusSeconds(300),
				null,
				null);
	}
}
