package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.ActivationIdentityChallengeStore;

@Repository
public class PostgresqlActivationIdentityChallengeStore implements ActivationIdentityChallengeStore {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlActivationIdentityChallengeStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<ActivationIdentityChallenge> findCurrentByInvitationAndUser(UUID invitationID, UUID userID) {
		return jdbcTemplate.query("""
				SELECT *
				FROM account_activation_identity_challenges
				WHERE invitation_id = ?
				  AND user_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				""", this::read, invitationID, userID)
				.stream().findFirst();
	}

	@Override
	@Transactional
	public void replaceCurrent(ActivationIdentityChallenge newChallenge, Instant revokedAt) {
		Objects.requireNonNull(newChallenge, "newChallenge");
		Objects.requireNonNull(revokedAt, "revokedAt");

		if (!newChallenge.isCurrent(revokedAt)
				|| newChallenge.failedAttempts() != 0
				|| newChallenge.sendRecoveryAt() == null
				|| !newChallenge.sendRecoveryAt().isAfter(newChallenge.createdAt())
				|| newChallenge.phoneNumber() == null
				|| newChallenge.phoneNumber().isBlank()
				|| newChallenge.codeDigest().isBlank()) {
			throw new IllegalArgumentException("Le nouveau défi d'identité est invalide.");
		}

		jdbcTemplate.queryForObject("""
				SELECT user_id
				FROM user_accounts
				WHERE user_id = ?
				FOR UPDATE
				""", UUID.class, newChallenge.userID());

		jdbcTemplate.update("""
				UPDATE account_activation_identity_challenges
				SET revoked_at = ?
				WHERE user_id = ?
				  AND invitation_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				""",
				atUtc(revokedAt),
				newChallenge.userID(),
				newChallenge.invitationID());

		jdbcTemplate.update("""
				INSERT INTO account_activation_identity_challenges
				    (challenge_id, user_id, invitation_id,
				     phone_number, code_digest, created_at, expires_at, send_confirmed_at, send_recovery_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				newChallenge.challengeID(),
				newChallenge.userID(),
				newChallenge.invitationID(),
				newChallenge.phoneNumber(),
				newChallenge.codeDigest(),
				atUtc(newChallenge.createdAt()),
				atUtc(newChallenge.expiresAt()),
				atUtc(newChallenge.sendConfirmedAt()),
				atUtc(newChallenge.sendRecoveryAt()));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recordFailedAttempt(UUID challengeID, Instant attemptedAt) {
		Objects.requireNonNull(challengeID, "challengeID");
		Objects.requireNonNull(attemptedAt, "attemptedAt");

		return jdbcTemplate.query("""
				UPDATE account_activation_identity_challenges
				SET failed_attempts = failed_attempts + 1,
				    last_failed_at = ?,
				    revoked_at = CASE
				        WHEN failed_attempts + 1 >= ? THEN ?
				        ELSE revoked_at
				    END
				WHERE challenge_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				  AND expires_at > ?
				  AND failed_attempts < ?
				RETURNING failed_attempts
				""",
				(row, rowNumber) -> row.getInt("failed_attempts"),
				atUtc(attemptedAt),
				ActivationIdentityChallenge.MAX_FAILED_ATTEMPTS,
				atUtc(attemptedAt),
				challengeID,
				atUtc(attemptedAt),
				ActivationIdentityChallenge.MAX_FAILED_ATTEMPTS)
				.stream().findFirst().orElse(0);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean consumeIfCurrent(UUID challengeID, Instant now) {
		Objects.requireNonNull(challengeID, "challengeID");
		Objects.requireNonNull(now, "now");

		return jdbcTemplate.update("""
				UPDATE account_activation_identity_challenges
				SET consumed_at = ?
				WHERE challenge_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				  AND code_digest IS NOT NULL
				  AND send_confirmed_at IS NOT NULL
				  AND failed_attempts < ?
				  AND expires_at > ?
				""",
				atUtc(now),
				challengeID,
				ActivationIdentityChallenge.MAX_FAILED_ATTEMPTS,
				atUtc(now)) == 1;
	}

	@Override
	public int countByInvitationAndUser(UUID invitationID, UUID userID) {
		Objects.requireNonNull(invitationID, "L'identifiant de l'invitation est obligatoire.");
		Objects.requireNonNull(userID, "L'identifiant du compte est obligatoire.");

		return jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM account_activation_identity_challenges
				WHERE invitation_id = ?
				  AND user_id = ?
				""", Integer.class, invitationID, userID);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean revokeCurrent(UUID challengeID, Instant revokedAt) {
		Objects.requireNonNull(challengeID, "L'identifiant du défi est obligatoire.");
		Objects.requireNonNull(revokedAt, "La date de révocation est obligatoire.");

		return jdbcTemplate.update("""
				UPDATE account_activation_identity_challenges
				SET revoked_at = ?
				WHERE challenge_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				""",
				atUtc(revokedAt),
				challengeID) == 1;
	}

	private ActivationIdentityChallenge read(ResultSet row, int rowNumber) throws SQLException {
		return new ActivationIdentityChallenge(
				row.getObject("challenge_id", UUID.class),
				row.getObject("user_id", UUID.class),
				row.getString("phone_number"),
				row.getObject("invitation_id", UUID.class),
				row.getString("code_digest"),
				row.getInt("failed_attempts"),
				readInstant(row, "created_at"),
				readInstant(row, "expires_at"),
				readInstant(row, "consumed_at"),
				readInstant(row, "revoked_at"),
				readInstant(row, "send_confirmed_at"),
				readInstant(row, "send_recovery_at"));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean confirmSend(UUID challengeID, Instant confirmedAt) {
		Objects.requireNonNull(challengeID, "L'identifiant du défi est obligatoire.");
		Objects.requireNonNull(confirmedAt, "La date de confirmation est obligatoire.");
		return jdbcTemplate.update("""
				UPDATE account_activation_identity_challenges
				SET send_confirmed_at = ?
				WHERE challenge_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
				  AND send_confirmed_at IS NULL AND created_at <= ?
				  AND send_recovery_at > ? AND expires_at > ?
				""", atUtc(confirmedAt), challengeID, atUtc(confirmedAt), atUtc(confirmedAt), atUtc(confirmedAt)) == 1;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int revokeUnconfirmedSends(Instant now) {
		Objects.requireNonNull(now, "La date de récupération est obligatoire.");
		return jdbcTemplate.update("""
				WITH due AS (
				    SELECT challenge_id FROM account_activation_identity_challenges
				    WHERE send_confirmed_at IS NULL AND consumed_at IS NULL AND revoked_at IS NULL
				      AND send_recovery_at <= ?
				    ORDER BY send_recovery_at, challenge_id
				    LIMIT 100 FOR UPDATE SKIP LOCKED
				)
				UPDATE account_activation_identity_challenges challenge
				SET revoked_at = ? FROM due WHERE challenge.challenge_id = due.challenge_id
				""", atUtc(now), atUtc(now));
	}

	private Instant readInstant(ResultSet row, String column) throws SQLException {
		OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime atUtc(Instant value) {
		return value == null ? null : value.atOffset(ZoneOffset.UTC);
	}
}
