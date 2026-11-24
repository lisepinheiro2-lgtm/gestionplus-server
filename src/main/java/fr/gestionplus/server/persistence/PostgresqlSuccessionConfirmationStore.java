package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SuccessionConfirmation;
import fr.gestionplus.server.account.SuccessionConfirmationStore;

@Repository
public class PostgresqlSuccessionConfirmationStore implements SuccessionConfirmationStore {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlSuccessionConfirmationStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void replaceCurrent(SuccessionConfirmation confirmation) {
		Objects.requireNonNull(confirmation, "La confirmation est obligatoire.");
		OffsetDateTime confirmedAt = confirmation.confirmedAt().atOffset(ZoneOffset.UTC);
		int changed = jdbcTemplate.update("""
				INSERT INTO succession_confirmations
				    (offer_id, user_id, session_version, session_id_hash, preview_digest, confirmed_at, expires_at)
				SELECT offer_id, ?, ?, ?, preview_digest, ?, ?
				FROM succession_offers
				WHERE offer_id = ? AND status = 'ACCEPTED'
				  AND ? IN (outgoing_user_id, successor_user_id)
				  AND preview_digest = ? AND created_at <= ? AND expires_at > ?
				ON CONFLICT (offer_id, user_id) DO UPDATE
				SET session_version = EXCLUDED.session_version,
				    session_id_hash = EXCLUDED.session_id_hash,
				    preview_digest = EXCLUDED.preview_digest,
				    confirmed_at = EXCLUDED.confirmed_at,
				    expires_at = EXCLUDED.expires_at
				""", confirmation.userID(), confirmation.sessionVersion(), confirmation.sessionIDHash(),
				confirmedAt, confirmation.expiresAt().atOffset(ZoneOffset.UTC), confirmation.offerID(),
				confirmation.userID(), confirmation.previewDigest(), confirmedAt, confirmedAt);

		if (changed != 1) {
			throw new AccessDeniedException("La confirmation ne correspond pas à une offre de succession valable.");
		}

	}

	@Override
	public Optional<SuccessionConfirmation> findByOfferIDAndUserID(UUID offerID, UUID userID) {
		return jdbcTemplate.query("""
				SELECT offer_id, user_id, session_version, session_id_hash, preview_digest, confirmed_at, expires_at
				FROM succession_confirmations WHERE offer_id = ? AND user_id = ?
				""", this::read, offerID, userID).stream().findFirst();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void deleteByOfferID(UUID offerID) {
		jdbcTemplate.update("DELETE FROM succession_confirmations WHERE offer_id = ?", offerID);
	}

	private SuccessionConfirmation read(ResultSet row, int rowNumber) throws SQLException {
		return new SuccessionConfirmation(row.getObject("offer_id", UUID.class), row.getObject("user_id", UUID.class),
				row.getLong("session_version"), row.getString("session_id_hash"), row.getString("preview_digest"),
				row.getObject("confirmed_at", OffsetDateTime.class).toInstant(),
				row.getObject("expires_at", OffsetDateTime.class).toInstant());
	}
}
