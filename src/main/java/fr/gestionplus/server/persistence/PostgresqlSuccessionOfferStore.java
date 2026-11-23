package fr.gestionplus.server.persistence;

import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.SuccessionOffer;
import fr.gestionplus.server.account.SuccessionOffer.Status;
import fr.gestionplus.server.account.SuccessionOfferStore;

@Repository
public class PostgresqlSuccessionOfferStore implements SuccessionOfferStore {

	private final JdbcTemplate jdbcTemplate;
	private final Clock clock;

	public PostgresqlSuccessionOfferStore(JdbcTemplate jdbcTemplate, Clock clock) {
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
		this.clock = Objects.requireNonNull(clock);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void create(SuccessionOffer offer) {
		Objects.requireNonNull(offer, "L’offre est obligatoire.");
		if (offer.status() != Status.PENDING || new HashSet<>(offer.adminRolesToTransfer()).size() != offer.adminRolesToTransfer().size()) {
			throw new IllegalArgumentException("L’offre de succession est invalide.");
		}
		for (UUID userID : new TreeSet<>(List.of(offer.outgoingUserID(), offer.successorUserID()))) {
			if (jdbcTemplate.queryForList("SELECT user_id FROM user_accounts WHERE user_id = ? FOR KEY SHARE", UUID.class, userID).isEmpty()) {
				throw denied();
			}
		}
		List<UUID> holders = jdbcTemplate.queryForList("""
				SELECT super_admin_user_id FROM tenant_organizations
				WHERE tenant_id = ? FOR UPDATE
				""", UUID.class, offer.tenantID());
		if (holders.isEmpty() || !offer.outgoingUserID().equals(holders.getFirst())) {
			throw denied();
		}
		Instant now = clock.instant();
		if (offer.createdAt().isAfter(now) || !now.isBefore(offer.expiresAt())) {
			throw denied();
		}
		boolean exists = jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM succession_offers
				    WHERE tenant_id = ? AND status IN ('PENDING', 'ACCEPTED') AND expires_at > ?
				)
				""", Boolean.class, offer.tenantID(), atUtc(now));
		if (exists) {
			throw denied();
		}
		jdbcTemplate.execute((ConnectionCallback<Void>) connection -> {
			Array roles = connection.createArrayOf("uuid", offer.adminRolesToTransfer().toArray(UUID[]::new));
			try (PreparedStatement statement = connection.prepareStatement("""
					INSERT INTO succession_offers
					    (offer_id, tenant_id, outgoing_user_id, successor_user_id, admin_roles_to_transfer,
					     preview_digest, status, created_at, expires_at)
					VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
					""")) {
				statement.setObject(1, offer.offerID());
				statement.setObject(2, offer.tenantID());
				statement.setObject(3, offer.outgoingUserID());
				statement.setObject(4, offer.successorUserID());
				statement.setArray(5, roles);
				statement.setString(6, offer.previewDigest());
				statement.setString(7, offer.status().name());
				statement.setObject(8, atUtc(offer.createdAt()));
				statement.setObject(9, atUtc(offer.expiresAt()));
				statement.executeUpdate();
				return null;
			} finally {
				roles.free();
			}
		});
	}

	@Override
	public Optional<SuccessionOffer> findById(UUID offerID) {
		Objects.requireNonNull(offerID, "L’identifiant de l’offre est obligatoire.");
		return jdbcTemplate.query("SELECT * FROM succession_offers WHERE offer_id = ?", this::read, offerID).stream().findFirst();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<SuccessionOffer> findByIdForUpdate(UUID offerID) {
		Objects.requireNonNull(offerID, "L’identifiant de l’offre est obligatoire.");
		return jdbcTemplate.query("SELECT * FROM succession_offers WHERE offer_id = ? FOR UPDATE", this::read, offerID).stream().findFirst();
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean cancel(UUID offerID, Status expectedStatus, Status newStatus) {
		return newStatus == Status.CANCELLED && updateStatus(offerID, expectedStatus, newStatus);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean complete(UUID offerID, Status expectedStatus, Status newStatus) {
		return expectedStatus == Status.ACCEPTED && newStatus == Status.COMPLETED && updateStatus(offerID, expectedStatus, newStatus);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public boolean updateStatus(UUID offerID, Status expectedStatus, Status newStatus) {
		if (offerID == null || expectedStatus == null || newStatus == null
				|| !(expectedStatus == Status.PENDING && (newStatus == Status.ACCEPTED || newStatus == Status.CANCELLED)
						|| expectedStatus == Status.ACCEPTED && (newStatus == Status.CANCELLED || newStatus == Status.COMPLETED))) {
			return false;
		}
		SuccessionOffer current = findByIdForUpdate(offerID).orElse(null);
		if (current == null || current.status() != expectedStatus || !clock.instant().isBefore(current.expiresAt())) {
			return false;
		}
		return jdbcTemplate.update("UPDATE succession_offers SET status = ? WHERE offer_id = ? AND status = ?",
				newStatus.name(), offerID, expectedStatus.name()) == 1;
	}

	private SuccessionOffer read(ResultSet row, int rowNumber) throws SQLException {
		Array roles = row.getArray("admin_roles_to_transfer");
		try {
			return new SuccessionOffer(row.getObject("offer_id", UUID.class), row.getObject("outgoing_user_id", UUID.class),
					row.getObject("successor_user_id", UUID.class), row.getObject("tenant_id", UUID.class),
					Arrays.stream((Object[]) roles.getArray()).map(UUID.class::cast).toList(), row.getString("preview_digest"),
					Status.valueOf(row.getString("status")), readInstant(row, "created_at"), readInstant(row, "expires_at"));
		} finally {
			roles.free();
		}
	}

	private static Instant readInstant(ResultSet row, String column) throws SQLException {
		return row.getObject(column, OffsetDateTime.class).toInstant();
	}

	private static OffsetDateTime atUtc(Instant instant) {
		return instant.atOffset(ZoneOffset.UTC);
	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException("Création d’offre de succession impossible ou état modifié.");
	}
}
