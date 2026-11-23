package fr.gestionplus.server.persistence;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.PrivilegedConfirmationProofStore;
import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;

@Repository
public class PostgresqlPrivilegedConfirmationProofStore implements PrivilegedConfirmationProofStore {

	private static final Duration MAX_VALIDITY = Duration.ofMinutes(5);
	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public PostgresqlPrivilegedConfirmationProofStore(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void replaceCurrent(Proof proof) {
		Objects.requireNonNull(proof, "La preuve est obligatoire.");
		if (proof.sessionVersion() < 0 || !isDigest(proof.previewDigest()) || !isDigest(proof.proofHash())) {
			throw new IllegalArgumentException("La preuve de confirmation est invalide.");
		}
		if (!lockCurrentAccount(proof.userID(), proof.sessionVersion())) {
			throw new IllegalArgumentException("Le compte ou sa version de sécurité n’est plus valide.");
		}
		Instant createdAt = clock.instant();
		if (!proof.expiresAt().isAfter(createdAt) || proof.expiresAt().isAfter(createdAt.plus(MAX_VALIDITY))) {
			throw new IllegalArgumentException("La preuve doit expirer dans les cinq minutes.");
		}
		jdbcTemplate.update("""
				INSERT INTO privileged_confirmation_proofs
				    (user_id, action, session_version, preview_digest, proof_hash, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				ON CONFLICT (user_id, action) DO UPDATE
				SET session_version = EXCLUDED.session_version,
				    preview_digest = EXCLUDED.preview_digest,
				    proof_hash = EXCLUDED.proof_hash,
				    created_at = EXCLUDED.created_at,
				    expires_at = EXCLUDED.expires_at
				""", proof.userID(), proof.action().name(), proof.sessionVersion(), proof.previewDigest(),
				proof.proofHash(), atUtc(createdAt), atUtc(proof.expiresAt()));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean consumeIfValid(UUID userID, long sessionVersion, Action action, String previewDigest,
			String proofHash, Instant now) {
		if (userID == null || sessionVersion < 0 || action == null || !isDigest(previewDigest)
				|| !isDigest(proofHash) || now == null || !lockCurrentAccount(userID, sessionVersion)) {
			return false;
		}
		Instant checkedAt = clock.instant();
		if (now.isAfter(checkedAt)) {
			checkedAt = now;
		}
		return jdbcTemplate.update("""
				DELETE FROM privileged_confirmation_proofs
				WHERE user_id = ? AND session_version = ? AND action = ?
				  AND preview_digest = ? AND proof_hash = ?
				  AND created_at <= ? AND expires_at > ?
				""", userID, sessionVersion, action.name(), previewDigest, proofHash,
				atUtc(checkedAt), atUtc(checkedAt)) == 1;
	}

	private boolean lockCurrentAccount(UUID userID, long sessionVersion) {
		return !jdbcTemplate.queryForList("""
				SELECT user_id FROM user_accounts
				WHERE user_id = ? AND session_version = ? AND status = 'ACTIVE'
				FOR UPDATE
				""", UUID.class, userID, sessionVersion).isEmpty();
	}

	private static boolean isDigest(String value) {
		return value != null && value.matches("[0-9a-f]{64}");
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}
}
