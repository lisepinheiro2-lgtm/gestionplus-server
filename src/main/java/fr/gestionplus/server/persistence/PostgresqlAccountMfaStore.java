package fr.gestionplus.server.persistence;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.auth.AccountMfaStore;

@Repository
public class PostgresqlAccountMfaStore implements AccountMfaStore {

	private final JdbcTemplate jdbc;

	public PostgresqlAccountMfaStore(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public boolean hasState(UUID userID) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT EXISTS (SELECT 1 FROM account_mfa_state WHERE user_id = ?)
				""", Boolean.class, userID));
	}

	@Override
	public Optional<Factor> findFactor(UUID userID) {
		return jdbc.query("""
				SELECT encrypted_secret, last_accepted_step FROM account_mfa_state WHERE user_id = ?
				""", (row, rowNumber) -> new Factor(row.getBytes(1), row.getLong(2)), userID).stream().findFirst();
	}

	@Override
	public boolean advanceAcceptedStep(UUID userID, long step) {
		return jdbc.update("""
				UPDATE account_mfa_state SET last_accepted_step = ?
				WHERE user_id = ? AND encrypted_secret IS NOT NULL AND last_accepted_step < ?
				""", step, userID, step) == 1;
	}

	@Override
	public Optional<EnrollmentState> findEnrollment(UUID userID) {
		return jdbc.query("""
				SELECT enrollment_id, encrypted_secret, session_version, expires_at
				FROM account_mfa_enrollments WHERE user_id = ?
				""", (row, rowNumber) -> new EnrollmentState(row.getObject(1, UUID.class), userID, row.getBytes(2),
				row.getLong(3), row.getObject(4, OffsetDateTime.class).toInstant()), userID).stream().findFirst();
	}

	@Override
	public void saveEnrollment(EnrollmentState enrollment) {
		jdbc.update(
				"""
						INSERT INTO account_mfa_enrollments (user_id, enrollment_id, encrypted_secret, session_version, expires_at)
						VALUES (?, ?, ?, ?, ?)
						ON CONFLICT (user_id) DO UPDATE SET enrollment_id = EXCLUDED.enrollment_id,
						    encrypted_secret = EXCLUDED.encrypted_secret, session_version = EXCLUDED.session_version,
						    expires_at = EXCLUDED.expires_at
						""",
				enrollment.userID(), enrollment.enrollmentID(), enrollment.encryptedSecret(),
				enrollment.sessionVersion(), enrollment.expiresAt().atOffset(ZoneOffset.UTC));
	}

	@Override
	public void removeEnrollment(UUID userID) {
		jdbc.update("DELETE FROM account_mfa_enrollments WHERE user_id = ?", userID);
	}

	@Override
	@Transactional
	public void saveFactor(UUID userID, byte[] encryptedSecret, long lastStep, List<String> recoveryCodeHashes) {
		jdbc.update("""
				INSERT INTO account_mfa_state (user_id, encrypted_secret, last_accepted_step)
				VALUES (?, ?, ?)
				ON CONFLICT (user_id) DO UPDATE SET encrypted_secret = EXCLUDED.encrypted_secret,
				    last_accepted_step = EXCLUDED.last_accepted_step
				""", userID, encryptedSecret, lastStep);

		jdbc.update("DELETE FROM account_mfa_recovery_codes WHERE user_id = ?", userID);

		for (String hash : recoveryCodeHashes) {
			jdbc.update("INSERT INTO account_mfa_recovery_codes (user_id, code_hash) VALUES (?, ?)", userID, hash);
		}

	}

	@Override
	public boolean consumeRecoveryCode(UUID userID, String hash) {
		return jdbc.update("DELETE FROM account_mfa_recovery_codes WHERE user_id = ? AND code_hash = ?", userID,
				hash) == 1;
	}

	@Override
	@Transactional
	public void requireNewFactor(UUID userID) {
		jdbc.update("DELETE FROM account_mfa_recovery_codes WHERE user_id = ?", userID);
		jdbc.update("""
				UPDATE account_mfa_state SET encrypted_secret = NULL, last_accepted_step = -1 WHERE user_id = ?
				""", userID);
	}
}
