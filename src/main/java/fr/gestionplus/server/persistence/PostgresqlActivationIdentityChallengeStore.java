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
import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;

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

		if (!newChallenge.isUsable(revokedAt)
				|| newChallenge.failedAttempts() != 0
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
				     phone_number, code_digest, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""",
				newChallenge.challengeID(),
				newChallenge.userID(),
				newChallenge.invitationID(),
				newChallenge.phoneNumber(),
				newChallenge.codeDigest(),
				atUtc(newChallenge.createdAt()),
				atUtc(newChallenge.expiresAt()));
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
				  AND failed_attempts < ?
				  AND expires_at > ?
				""",
				atUtc(now),
				challengeID,
				ActivationIdentityChallenge.MAX_FAILED_ATTEMPTS,
				atUtc(now)) == 1;
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
				readInstant(row, "revoked_at"));
	}

	private Instant readInstant(ResultSet row, String column) throws SQLException {
		OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private OffsetDateTime atUtc(Instant value) {
		return value == null ? null : value.atOffset(ZoneOffset.UTC);
	}
}
