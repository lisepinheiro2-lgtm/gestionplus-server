package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SuccessionConfirmation;
import fr.gestionplus.server.account.SuccessionConfirmationStore;
import fr.gestionplus.server.account.SuccessionOfferStore;

@Repository
public class PostgresqlSuccessionConfirmationStore implements SuccessionConfirmationStore {

	private final JdbcTemplate jdbc;
	private final SuccessionOfferStore offers;

	public PostgresqlSuccessionConfirmationStore(JdbcTemplate jdbc, SuccessionOfferStore offers) {
		this.jdbc = jdbc;
		this.offers = offers;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void replaceCurrent(SuccessionConfirmation confirmation) {
		var offer = offers.findByIdForUpdate(confirmation.offerID());
		if (offer.isEmpty() || !offer.get().isOpenAt(confirmation.confirmedAt())
				|| !offer.get().isCurrentRevision(confirmation.revision())) {
			throw denied();
		}

		OffsetDateTime confirmedAt = confirmation.confirmedAt().atOffset(ZoneOffset.UTC);
		int changed = jdbc.update("""
				INSERT INTO succession_confirmations
				    (offer_id, user_id, revision, session_version, session_id_hash, preview_digest, confirmed_at, expires_at)
				SELECT consent.offer_id, consent.user_id, consent.revision, ?, ?, revision.preview_digest, ?, ?
				FROM succession_consents consent JOIN succession_revisions revision USING (offer_id, revision)
				WHERE consent.offer_id = ? AND consent.revision = ? AND consent.user_id = ?
				  AND revision.preview_digest = ? AND consent.accepted_at <= ?
				ON CONFLICT (offer_id, user_id) DO UPDATE
				SET revision = EXCLUDED.revision, session_version = EXCLUDED.session_version,
				    session_id_hash = EXCLUDED.session_id_hash, preview_digest = EXCLUDED.preview_digest,
				    confirmed_at = EXCLUDED.confirmed_at, expires_at = EXCLUDED.expires_at
				WHERE succession_confirmations.confirmed_at <= EXCLUDED.confirmed_at
				""", confirmation.sessionVersion(), confirmation.sessionIDHash(), confirmedAt,
				confirmation.expiresAt().atOffset(ZoneOffset.UTC), confirmation.offerID(), confirmation.revision(),
				confirmation.userID(), confirmation.previewDigest(), confirmedAt);
		if (changed != 1) {
			throw denied();
		}
	}

	@Override
	public Optional<SuccessionConfirmation> findByOfferIDAndUserID(UUID offerID, long revision, UUID userID) {
		return jdbc.query("""
				SELECT * FROM succession_confirmations WHERE offer_id = ? AND revision = ? AND user_id = ?
				""", this::read, offerID, revision, userID).stream().findFirst();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void deleteByOfferID(UUID offerID) {
		offers.findByIdForUpdate(offerID);
		jdbc.update("DELETE FROM succession_confirmations WHERE offer_id = ?", offerID);
	}

	private SuccessionConfirmation read(ResultSet row, int rowNumber) throws SQLException {
		return new SuccessionConfirmation(row.getObject("offer_id", UUID.class), row.getObject("user_id", UUID.class),
				row.getLong("revision"), row.getLong("session_version"), row.getString("session_id_hash"),
				row.getString("preview_digest"), row.getObject("confirmed_at", OffsetDateTime.class).toInstant(),
				row.getObject("expires_at", OffsetDateTime.class).toInstant());
	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException("La confirmation ne correspond pas à la révision courante de la proposition.");
	}
}
