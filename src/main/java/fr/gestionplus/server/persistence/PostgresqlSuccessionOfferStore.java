package fr.gestionplus.server.persistence;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SuccessionOffer;
import fr.gestionplus.server.account.SuccessionOffer.ClosureReason;
import fr.gestionplus.server.account.SuccessionOffer.OfferStatus;
import fr.gestionplus.server.account.SuccessionOfferStore;
import fr.gestionplus.server.account.SuccessionRevision;
import fr.gestionplus.server.account.SuccessionRevisionStore;

@Repository
public class PostgresqlSuccessionOfferStore implements SuccessionOfferStore {

	private final JdbcTemplate jdbc;
	private final Clock clock;
	private final SuccessionRevisionStore revisions;

	public PostgresqlSuccessionOfferStore(JdbcTemplate jdbc, Clock clock, SuccessionRevisionStore revisions) {
		this.jdbc = jdbc;
		this.clock = clock;
		this.revisions = revisions;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void create(SuccessionOffer offer, SuccessionRevision initialRevision) {
		if (offer.status() != OfferStatus.PENDING || offer.currentRevision() != 1 || initialRevision.revision() != 1
				|| !initialRevision.offerID().equals(offer.offerID())
				|| !initialRevision.preview().adminRolesToTransfer().equals(offer.adminRolesToTransfer())
				|| !initialRevision.createdAt().equals(offer.createdAt())) {
			throw new IllegalArgumentException("La proposition et sa première révision doivent correspondre.");
		}

		for (UUID userID : List.of(offer.outgoingUserID(), offer.successorUserID()).stream().sorted().toList()) {
			if (jdbc.queryForList("SELECT user_id FROM user_accounts WHERE user_id = ? FOR KEY SHARE",
					UUID.class, userID).isEmpty()) {
				throw denied();
			}
		}

		List<UUID> holders = jdbc.queryForList("""
				SELECT super_admin_user_id FROM tenant_organizations WHERE tenant_id = ? FOR UPDATE
				""", UUID.class, offer.tenantID());
		Instant now = clock.instant();
		if (holders.isEmpty() || !offer.outgoingUserID().equals(holders.getFirst()) || !offer.isOpenAt(now)) {
			throw denied();
		}

		jdbc.update("""
				UPDATE succession_offers SET status = 'EXPIRED', closure_reason = 'EXPIRATION', closed_at = expires_at
				WHERE tenant_id = ? AND status IN ('PENDING', 'ACCEPTED') AND expires_at <= ?
				""", offer.tenantID(), atUtc(now));
		int inserted = jdbc.update("""
				INSERT INTO succession_offers (offer_id, tenant_id, outgoing_user_id, successor_user_id,
				    admin_roles_to_transfer, current_revision, status, created_at, expires_at)
				VALUES (?, ?, ?, ?, CAST(? AS uuid[]), 1, 'PENDING', ?, ?)
				ON CONFLICT (tenant_id) WHERE status IN ('PENDING', 'ACCEPTED') DO NOTHING
				""", offer.offerID(), offer.tenantID(), offer.outgoingUserID(), offer.successorUserID(),
				uuidArray(offer.adminRolesToTransfer()), atUtc(offer.createdAt()), atUtc(offer.expiresAt()));
		if (inserted != 1) {
			throw denied();
		}
		revisions.create(initialRevision);
	}

	@Override
	public Optional<SuccessionOffer> findById(UUID offerID) {
		return jdbc.query("SELECT * FROM succession_offers WHERE offer_id = ?", this::read, offerID)
				.stream().findFirst();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<SuccessionOffer> findByIdForUpdate(UUID offerID) {
		return jdbc.query("SELECT * FROM succession_offers WHERE offer_id = ? FOR UPDATE", this::read, offerID)
				.stream().findFirst();
	}

	@Override
	public List<SuccessionOffer> findByParticipant(UUID userID) {
		return List.copyOf(jdbc.query("""
				SELECT * FROM succession_offers WHERE outgoing_user_id = ? OR successor_user_id = ?
				ORDER BY created_at DESC, offer_id
				""", this::read, userID, userID));
	}

	@Override
	public List<SuccessionOffer> findExpiredOffers(Instant cutoff, int limit) {
		return List.copyOf(jdbc.query("""
				SELECT * FROM succession_offers WHERE status IN ('PENDING', 'ACCEPTED') AND expires_at <= ?
				ORDER BY expires_at, offer_id LIMIT ?
				""", this::read, atUtc(cutoff), limit));
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean advanceRevision(UUID offerID, long expectedRevision, SuccessionRevision next) {
		if (!next.offerID().equals(offerID) || next.revision() != Math.incrementExact(expectedRevision)) {
			throw new IllegalArgumentException("La révision doit suivre immédiatement la précédente.");
		}
		Instant now = clock.instant();
		if (next.createdAt().isAfter(now)) {
			return false;
		}
		int changed = jdbc.update("""
				UPDATE succession_offers SET current_revision = ?, admin_roles_to_transfer = CAST(? AS uuid[]),
				    status = 'PENDING'
				WHERE offer_id = ? AND current_revision = ? AND status IN ('PENDING', 'ACCEPTED')
				    AND expires_at > ?
				  AND EXISTS (SELECT 1 FROM succession_revisions old
				      WHERE old.offer_id = succession_offers.offer_id AND old.revision = current_revision
				        AND old.preview_digest <> ? AND old.created_at <= ?)
				""", next.revision(), uuidArray(next.preview().adminRolesToTransfer()), offerID, expectedRevision,
				atUtc(now), next.previewDigest(), atUtc(next.createdAt()));
		if (changed == 0) {
			return false;
		}
		revisions.create(next);
		return true;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean markAccepted(UUID offerID, long expectedRevision, Instant acceptedAt) {
		var current = findByIdForUpdate(offerID);
		if (current.isEmpty() || !current.get().isOpenAt(acceptedAt) || !current.get().isCurrentRevision(expectedRevision)) {
			return false;
		}
		return jdbc.update("""
				UPDATE succession_offers SET status = 'ACCEPTED'
				WHERE offer_id = ? AND status = 'PENDING' AND expires_at > ?
				  AND 2 = (SELECT count(*) FROM succession_consents
				      WHERE offer_id = ? AND revision = ? AND accepted_at <= ?)
				""", offerID, atUtc(clock.instant()), offerID, expectedRevision, atUtc(acceptedAt)) == 1;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean close(UUID offerID, long expectedRevision, ClosureReason reason, Instant closedAt) {
		return switch (reason) {
		case TRANSFERRED, LEGACY_INCOMPATIBLE -> throw new IllegalArgumentException("Motif réservé.");
		case EXPIRATION -> jdbc.update("""
				UPDATE succession_offers SET status = 'EXPIRED', closure_reason = 'EXPIRATION', closed_at = expires_at
				WHERE offer_id = ? AND current_revision = ? AND status IN ('PENDING', 'ACCEPTED') AND expires_at <= ?
				""", offerID, expectedRevision, atUtc(earlier(closedAt, clock.instant()))) == 1;
		default -> jdbc.update("""
				UPDATE succession_offers SET status = 'CANCELLED', closure_reason = ?, closed_at = ?
				WHERE offer_id = ? AND current_revision = ? AND status IN ('PENDING', 'ACCEPTED')
				  AND created_at <= ? AND expires_at > GREATEST(?, ?)
				""", reason.name(), atUtc(closedAt), offerID, expectedRevision, atUtc(closedAt), atUtc(closedAt),
				atUtc(clock.instant())) == 1;
		};
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean complete(UUID offerID, long expectedRevision, Instant authorizedAt) {
		if (findByIdForUpdate(offerID).isEmpty()) {
			return false;
		}
		return jdbc.update("""
				UPDATE succession_offers SET status = 'COMPLETED', closure_reason = 'TRANSFERRED', closed_at = ?
				WHERE offer_id = ? AND current_revision = ? AND status = 'ACCEPTED'
				  AND created_at <= ? AND expires_at > ?
				  AND 2 = (SELECT count(*) FROM succession_confirmations
				      WHERE offer_id = ? AND revision = ? AND confirmed_at <= ? AND expires_at > ?)
				""", atUtc(authorizedAt), offerID, expectedRevision, atUtc(authorizedAt), atUtc(authorizedAt),
				offerID, expectedRevision, atUtc(authorizedAt), atUtc(authorizedAt)) == 1;
	}

	private SuccessionOffer read(ResultSet row, int rowNumber) throws SQLException {
		Array roles = row.getArray("admin_roles_to_transfer");
		try {
			String reason = row.getString("closure_reason");
			return new SuccessionOffer(row.getObject("offer_id", UUID.class), row.getObject("outgoing_user_id", UUID.class),
					row.getObject("successor_user_id", UUID.class), row.getObject("tenant_id", UUID.class),
					Arrays.stream((Object[]) roles.getArray()).map(UUID.class::cast).toList(),
					row.getObject("current_revision", Long.class), OfferStatus.valueOf(row.getString("status")),
					readInstant(row, "created_at"), readInstant(row, "expires_at"),
					reason == null ? null : ClosureReason.valueOf(reason), readInstant(row, "closed_at"));
		} finally {
			roles.free();
		}
	}

	private static String uuidArray(List<UUID> ids) {
		return "{" + String.join(",", ids.stream().map(UUID::toString).toList()) + "}";
	}

	private static Instant readInstant(ResultSet row, String column) throws SQLException {
		OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
		return value == null ? null : value.toInstant();
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static Instant earlier(Instant first, Instant second) {
		return first.isBefore(second) ? first : second;
	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException("Création d’offre de succession impossible ou état modifié.");
	}
}
