package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SmsIdentityChallenge;
import fr.gestionplus.server.account.SmsIdentityChallenge.Binding;
import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;
import fr.gestionplus.server.account.SmsIdentityChallengeStore;

@Repository
public class PostgresqlSmsIdentityChallengeStore implements SmsIdentityChallengeStore {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlSmsIdentityChallengeStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public Optional<SmsIdentityChallenge> findCurrentByInvitationAndUser(UUID invitationID, UUID userID) {
		return jdbcTemplate.query("""
				SELECT *
				FROM sms_identity_challenges
				WHERE purpose = 'ACCOUNT_ACTIVATION' AND invitation_id = ?
				  AND user_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				""", this::read, invitationID, userID)
				.stream().findFirst();
	}

	@Override
	@Transactional
	public void replaceCurrent(SmsIdentityChallenge newChallenge, Instant revokedAt) {
		Objects.requireNonNull(newChallenge, "newChallenge");
		Objects.requireNonNull(revokedAt, "revokedAt");

		if (!newChallenge.isCurrent(revokedAt)
				|| newChallenge.failedAttempts() != 0
				|| newChallenge.binding().phoneNumber().isBlank()
				|| newChallenge.codeDigest().isBlank()) {
			throw new IllegalArgumentException("Le nouveau défi d'identité est invalide.");
		}

		Binding binding = newChallenge.binding();
		jdbcTemplate.queryForObject("""
				SELECT user_id
				FROM user_accounts
				WHERE user_id = ?
				FOR UPDATE
				""", UUID.class, binding.userID());

		jdbcTemplate.update("""
				UPDATE sms_identity_challenges
				SET revoked_at = ?
				WHERE user_id = ?
				  AND purpose = ?
				  AND invitation_id IS NOT DISTINCT FROM CAST(? AS uuid)
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				""",
				atUtc(revokedAt),
				binding.userID(),
				binding.purpose().name(),
				binding.invitationID());

		jdbcTemplate.update("""
				INSERT INTO sms_identity_challenges
				    (challenge_id, user_id, purpose, invitation_id, session_id_hash, session_version,
				     phone_number, code_digest, created_at, expires_at, send_confirmed_at, send_recovery_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
				""",
				newChallenge.challengeID(),
				binding.userID(),
				binding.purpose().name(),
				binding.invitationID(),
				binding.sessionIDHash(),
				binding.sessionVersion(),
				binding.phoneNumber(),
				newChallenge.codeDigest(),
				atUtc(newChallenge.createdAt()),
				atUtc(newChallenge.expiresAt()),
				atUtc(newChallenge.sendConfirmedAt()),
				atUtc(newChallenge.sendRecoveryAt()));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int recordFailedAttempt(Purpose purpose, UUID challengeID, Instant attemptedAt) {
		Objects.requireNonNull(challengeID, "challengeID");
		Objects.requireNonNull(attemptedAt, "attemptedAt");

		return jdbcTemplate.query("""
				UPDATE sms_identity_challenges
				SET failed_attempts = failed_attempts + 1,
				    last_failed_at = ?,
				    revoked_at = CASE
				        WHEN failed_attempts + 1 >= ? THEN ?
				        ELSE revoked_at
				    END
				WHERE purpose = ? AND challenge_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				  AND expires_at > ?
				  AND failed_attempts < ?
				RETURNING failed_attempts
				""",
				(row, rowNumber) -> row.getInt("failed_attempts"),
				atUtc(attemptedAt),
				SmsIdentityChallenge.MAX_FAILED_ATTEMPTS,
				atUtc(attemptedAt),
				purpose.name(),
				challengeID,
				atUtc(attemptedAt),
				SmsIdentityChallenge.MAX_FAILED_ATTEMPTS)
				.stream().findFirst().orElse(0);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean consumeIfCurrent(Purpose purpose, UUID challengeID, Instant now) {
		Objects.requireNonNull(challengeID, "challengeID");
		Objects.requireNonNull(now, "now");

		return jdbcTemplate.update("""
				UPDATE sms_identity_challenges
				SET consumed_at = ?
				WHERE purpose = ? AND challenge_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				  AND code_digest IS NOT NULL
				  AND send_confirmed_at IS NOT NULL
				  AND failed_attempts < ?
				  AND expires_at > ?
				""",
				atUtc(now),
				purpose.name(),
				challengeID,
				SmsIdentityChallenge.MAX_FAILED_ATTEMPTS,
				atUtc(now)) == 1;
	}

	@Override
	public int countByInvitationAndUser(UUID invitationID, UUID userID) {
		Objects.requireNonNull(invitationID, "L'identifiant de l'invitation est obligatoire.");
		Objects.requireNonNull(userID, "L'identifiant du compte est obligatoire.");

		return jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM sms_identity_challenges
				WHERE purpose = 'ACCOUNT_ACTIVATION' AND invitation_id = ?
				  AND user_id = ?
				""", Integer.class, invitationID, userID);
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public boolean revokeCurrent(Purpose purpose, UUID challengeID, Instant revokedAt) {
		Objects.requireNonNull(challengeID, "L'identifiant du défi est obligatoire.");
		Objects.requireNonNull(revokedAt, "La date de révocation est obligatoire.");

		return jdbcTemplate.update("""
				UPDATE sms_identity_challenges
				SET revoked_at = ?
				WHERE purpose = ? AND challenge_id = ?
				  AND consumed_at IS NULL
				  AND revoked_at IS NULL
				""",
				atUtc(revokedAt),
				purpose.name(),
				challengeID) == 1;
	}

	@Override
	public Optional<SmsIdentityChallenge> findCurrentMfaByUser(UUID userID) {
		return jdbcTemplate.query("""
				SELECT *
				FROM sms_identity_challenges
				WHERE purpose = 'MFA_ENROLLMENT' AND user_id = ?
				  AND consumed_at IS NULL AND revoked_at IS NULL
				""", this::read, userID).stream().findFirst();
	}

	@Override
	public List<Issuance> findRecentMfaIssuances(UUID userID, Instant since, int limit) {
		return jdbcTemplate.query("""
				SELECT created_at, COALESCE(send_confirmed_at, created_at) AS sent_at,
				    CASE WHEN send_confirmed_at IS NULL AND revoked_at IS NULL THEN send_recovery_at END AS pending_until
				FROM sms_identity_challenges
				WHERE user_id = ? AND purpose = 'MFA_ENROLLMENT' AND created_at > ?
				ORDER BY created_at DESC, challenge_id DESC
				LIMIT ?
				""", (row, index) -> new Issuance(readInstant(row, "created_at"), readInstant(row, "sent_at"),
						readInstant(row, "pending_until")), userID, atUtc(since), limit);
	}

	private SmsIdentityChallenge read(ResultSet row, int rowNumber) throws SQLException {
		return new SmsIdentityChallenge(
				row.getObject("challenge_id", UUID.class),
				new Binding(row.getObject("user_id", UUID.class), row.getString("phone_number"),
						Purpose.valueOf(row.getString("purpose")), row.getObject("invitation_id", UUID.class),
						row.getString("session_id_hash"), row.getObject("session_version", Long.class)),
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
	public boolean confirmSend(Purpose purpose, UUID challengeID, Instant confirmedAt) {
		Objects.requireNonNull(challengeID, "L'identifiant du défi est obligatoire.");
		Objects.requireNonNull(confirmedAt, "La date de confirmation est obligatoire.");
		return jdbcTemplate.update("""
				UPDATE sms_identity_challenges
				SET send_confirmed_at = ?
				WHERE purpose = ? AND challenge_id = ? AND consumed_at IS NULL AND revoked_at IS NULL
				  AND send_confirmed_at IS NULL AND created_at <= ?
				  AND send_recovery_at > ? AND expires_at > ?
				""", atUtc(confirmedAt), purpose.name(), challengeID,
				atUtc(confirmedAt), atUtc(confirmedAt), atUtc(confirmedAt)) == 1;
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public int revokeUnconfirmedSends(Instant now) {
		Objects.requireNonNull(now, "La date de récupération est obligatoire.");
		return jdbcTemplate.update("""
				WITH due AS (
				    SELECT challenge_id FROM sms_identity_challenges
				    WHERE send_confirmed_at IS NULL AND consumed_at IS NULL AND revoked_at IS NULL
				      AND send_recovery_at <= ?
				    ORDER BY send_recovery_at, challenge_id
				    LIMIT 100 FOR UPDATE SKIP LOCKED
				)
				UPDATE sms_identity_challenges challenge
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
