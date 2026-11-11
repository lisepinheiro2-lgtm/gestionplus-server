package fr.gestionplus.server.persistence;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditFallback;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;

@Component
@EnableScheduling
@ConditionalOnProperty(prefix = "gestionplus.audit.fallback", name = { "key", "directory" })
public class PostgresqlAuditFallback implements AuditFallback {

	private static final Logger LOGGER = LoggerFactory.getLogger(PostgresqlAuditFallback.class);

	private final JdbcTemplate jdbc;
	private final TransactionTemplate replayTransaction;
	private final EncryptedAuditCodec codec;
	private final AuditFileJournal files;
	private final int maxEvents;

	public PostgresqlAuditFallback(JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
			@Value("${gestionplus.audit.fallback.key}") String key,
			@Value("${gestionplus.audit.fallback.directory}") String directory,
			@Value("${gestionplus.audit.fallback.max-events:10000}") int maxEvents) {
		this.jdbc = jdbc;
		this.maxEvents = maxEvents;
		codec = new EncryptedAuditCodec(key);
		files = new AuditFileJournal(Path.of(directory), maxEvents);
		replayTransaction = new TransactionTemplate(transactionManager);
		replayTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
	}

	@Override
	public void writeInBusinessTransaction(AuditEvent event) {
		jdbc.execute((ConnectionCallback<Void>) connection -> {

			if (connection.getAutoCommit()) {
				throw new IllegalStateException("Le journal transactionnel exige la connexion métier.");
			}

			Savepoint beforeAudit = connection.setSavepoint();

			try {
				insertPrimary(connection, event);
			} catch (SQLException failure) {
				connection.rollback(beforeAudit);

				if (!isAuditUnavailable(failure)) {
					throw failure;
				}

				insertPending(connection, event);
				LOGGER.warn("Événement d’audit {} placé dans le journal transactionnel ({})", event.getEventId(),
						failure.getSQLState());
			}

			connection.releaseSavepoint(beforeAudit);
			return null;
		});
	}

	@Override
	public void writeIndependent(AuditEvent event) {
		files.append(event.getEventId(), codec.encrypt(event));
		LOGGER.warn("Événement d’audit {} enregistré dans le journal local de secours", event.getEventId());
	}

	@Scheduled(fixedDelay = 30000, initialDelay = 30000)
	public void reconcile() {
		replayPending();
		replayFiles();
	}

	private void replayPending() {
		List<UUID> ids;

		try {
			ids = jdbc.queryForList("""
					SELECT event_id FROM audit_pending_events ORDER BY occurred_at, event_id LIMIT 100
					""", UUID.class);
		} catch (RuntimeException failure) {
			LOGGER.warn("Lecture du journal d’audit transactionnel impossible ({})",
					failure.getClass().getSimpleName());
			return;
		}

		for (UUID eventId : ids) {

			try {
				replayTransaction
						.executeWithoutResult(transaction -> jdbc.execute((ConnectionCallback<Void>) connection -> {
							replayPending(connection, eventId);
							return null;
						}));
			} catch (DataAccessException | TransactionException failure) {
				LOGGER.warn("Réconciliation PostgreSQL bloquée pour l’événement {} ({})", eventId,
						failure.getClass().getSimpleName());
				return;
			} catch (RuntimeException failure) {
				LOGGER.error("Événement transactionnel {} illisible ou corrompu ({})", eventId,
						failure.getClass().getSimpleName());
			}

		}

	}

	private void replayFiles() {
		List<Path> entries;

		try {
			entries = files.entries();
		} catch (RuntimeException failure) {
			LOGGER.error("Journal d’audit de secours inaccessible ({})", failure.getClass().getSimpleName());
			return;
		}

		for (Path entry : entries) {
			String name = entry.getFileName().toString();

			if (!name.endsWith(".audit")) {
				LOGGER.error("Écriture interrompue détectée dans le journal d’audit de secours.");
				continue;
			}

			UUID eventId = null;

			try {
				eventId = UUID.fromString(name.substring(0, name.length() - ".audit".length()));
				AuditEvent event = codec.decrypt(eventId, files.read(entry));

				replayTransaction
						.executeWithoutResult(transaction -> jdbc.execute((ConnectionCallback<Void>) connection -> {
							insertPrimary(connection, event);
							return null;
						}));
				files.remove(entry);
			} catch (DataAccessException | TransactionException failure) {
				LOGGER.warn("Réconciliation locale bloquée pour l’événement {} ({})", eventId,
						failure.getClass().getSimpleName());
				return;
			} catch (RuntimeException failure) {
				LOGGER.error("Entrée du journal local {} manquante, corrompue ou inaccessible ({})", eventId,
						failure.getClass().getSimpleName());
			}

		}

	}

	private void replayPending(Connection connection, UUID eventId) throws SQLException {
		byte[] payload;

		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT encrypted_event FROM audit_pending_events WHERE event_id = ? FOR UPDATE SKIP LOCKED
				""")) {
			statement.setObject(1, eventId);

			try (ResultSet row = statement.executeQuery()) {

				if (!row.next()) {
					return;
				}

				payload = row.getBytes(1);
			}

		}

		insertPrimary(connection, codec.decrypt(eventId, payload));

		try (PreparedStatement statement = connection
				.prepareStatement("DELETE FROM audit_pending_events WHERE event_id = ?")) {
			statement.setObject(1, eventId);
			statement.executeUpdate();
		}

	}

	private void insertPending(Connection connection, AuditEvent event) throws SQLException {

		try (Statement quotaLock = connection.createStatement()) {
			quotaLock.execute("SELECT pg_advisory_xact_lock(7161329083)");
		}

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO audit_pending_events (event_id, occurred_at, encrypted_event)
				SELECT ?, ?, ? WHERE (SELECT COUNT(*) FROM audit_pending_events) < ?
				""")) {
			statement.setObject(1, event.getEventId());
			statement.setObject(2, event.getOccurredAt().atOffset(ZoneOffset.UTC));
			statement.setBytes(3, codec.encrypt(event));
			statement.setInt(4, maxEvents);

			if (statement.executeUpdate() != 1) {
				throw new IllegalStateException("Le journal d’audit transactionnel est saturé.");
			}

		}

	}

	private void insertPrimary(Connection connection, AuditEvent event) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO audit_events
				    (event_id, occurred_at, actor_user_id, tenant_id, action, result, ip_address, metadata)
				VALUES (?, ?, ?, ?, ?, ?, ?, CAST(? AS jsonb))
				ON CONFLICT (event_id) DO NOTHING
				""")) {
			statement.setObject(1, event.getEventId());
			statement.setObject(2, event.getOccurredAt().atOffset(ZoneOffset.UTC));
			statement.setObject(3, event.getActorUserId());
			statement.setObject(4, event.getTenantId());
			statement.setString(5, event.getAction().name());
			statement.setString(6, event.getResult().name());
			statement.setString(7, event.getIpAddress());
			statement.setString(8, codec.metadataJson(event.getMetadata()));

			if (statement.executeUpdate() == 0) {
				requireSameEvent(connection, event);
				return;
			}

		}

		try (PreparedStatement statement = connection.prepareStatement("""
				INSERT INTO audit_targets (event_id, target_type, target_id) VALUES (?, ?, ?)
				""")) {

			for (AuditTarget target : event.getTargets()) {
				statement.setObject(1, event.getEventId());
				statement.setString(2, target.getTargetType().name());
				statement.setObject(3, target.getTargetId());
				statement.addBatch();
			}

			statement.executeBatch();
		}

	}

	private void requireSameEvent(Connection connection, AuditEvent event) throws SQLException {

		try (PreparedStatement statement = connection.prepareStatement("""
				SELECT occurred_at, actor_user_id, tenant_id, action, result, ip_address, metadata
				FROM audit_events WHERE event_id = ?
				""")) {
			statement.setObject(1, event.getEventId());

			try (ResultSet row = statement.executeQuery()) {

				if (!row.next()
						|| Duration.between(row.getObject("occurred_at", OffsetDateTime.class).toInstant(),
								event.getOccurredAt()).abs().compareTo(Duration.ofNanos(1000)) > 0
						|| !Objects.equals(row.getObject("actor_user_id", UUID.class), event.getActorUserId())
						|| !Objects.equals(row.getObject("tenant_id", UUID.class), event.getTenantId())
						|| !row.getString("action").equals(event.getAction().name())
						|| !row.getString("result").equals(event.getResult().name())
						|| !Objects.equals(row.getString("ip_address"), event.getIpAddress())
						|| !codec.readMetadata(row.getString("metadata")).equals(event.getMetadata())) {
					throw new IllegalStateException("Conflit de contenu pour un identifiant d’audit.");
				}

			}

		}

		Set<AuditTarget> targets = new HashSet<>();

		try (PreparedStatement statement = connection.prepareStatement(
				"SELECT target_type, target_id FROM audit_targets WHERE event_id = ?")) {
			statement.setObject(1, event.getEventId());

			try (ResultSet row = statement.executeQuery()) {

				while (row.next()) {
					targets.add(new AuditTarget(AuditTargetType.valueOf(row.getString("target_type")),
							row.getObject("target_id", UUID.class)));
				}

			}

		}

		if (!event.getTargets().equals(targets)) {
			throw new IllegalStateException("Conflit de cibles pour un identifiant d’audit.");
		}

	}

	private boolean isAuditUnavailable(SQLException failure) {
		String state = failure.getSQLState();
		return state != null && (state.startsWith("08") || state.startsWith("53") || state.startsWith("57")
				|| state.startsWith("58") || Set.of("42501", "42P01", "55P03").contains(state));
	}
}
