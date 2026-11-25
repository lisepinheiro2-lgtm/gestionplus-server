package fr.gestionplus.server.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SuccessionConsent;
import fr.gestionplus.server.account.SuccessionConsentStore;
import fr.gestionplus.server.account.SuccessionOfferStore;

@Repository
public class PostgresqlSuccessionConsentStore implements SuccessionConsentStore {

	private final JdbcTemplate jdbc;
	private final SuccessionOfferStore offers;

	public PostgresqlSuccessionConsentStore(JdbcTemplate jdbc, SuccessionOfferStore offers) {
		this.jdbc = jdbc;
		this.offers = offers;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean saveIfAbsent(SuccessionConsent consent) {
		var offer = offers.findByIdForUpdate(consent.offerID());
		if (offer.isEmpty() || !offer.get().isOpenAt(consent.acceptedAt())
				|| !offer.get().hasParticipant(consent.userID()) || !offer.get().isCurrentRevision(consent.revision())) {
			return false;
		}

		return jdbc.update("""
				INSERT INTO succession_consents (offer_id, revision, user_id, accepted_at)
				SELECT offer_id, revision, ?, ? FROM succession_revisions
				WHERE offer_id = ? AND revision = ? AND created_at <= ?
				ON CONFLICT (offer_id, revision, user_id) DO NOTHING
				""", consent.userID(), consent.acceptedAt().atOffset(ZoneOffset.UTC), consent.offerID(),
				consent.revision(), consent.acceptedAt().atOffset(ZoneOffset.UTC)) == 1;
	}

	@Override
	public Optional<SuccessionConsent> find(UUID offerID, long revision, UUID userID) {
		return jdbc.query("SELECT * FROM succession_consents WHERE offer_id = ? AND revision = ? AND user_id = ?",
				this::read, offerID, revision, userID).stream().findFirst();
	}

	@Override
	public List<SuccessionConsent> findByRevision(UUID offerID, long revision) {
		return List.copyOf(jdbc.query("SELECT * FROM succession_consents WHERE offer_id = ? AND revision = ? ORDER BY user_id",
				this::read, offerID, revision));
	}

	private SuccessionConsent read(ResultSet row, int rowNumber) throws SQLException {
		return new SuccessionConsent(row.getObject("offer_id", UUID.class), row.getObject("user_id", UUID.class),
				row.getLong("revision"), row.getObject("accepted_at", OffsetDateTime.class).toInstant());
	}
}
