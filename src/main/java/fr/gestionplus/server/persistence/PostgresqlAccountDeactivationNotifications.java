package fr.gestionplus.server.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import fr.gestionplus.server.account.AccountDeactivationNotifications;

@Repository
public class PostgresqlAccountDeactivationNotifications implements AccountDeactivationNotifications {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlAccountDeactivationNotifications(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public void enqueue(UUID actorUserID, UUID targetUserID, UUID structureID) {
		enqueue(actorUserID, targetUserID, structureID, Kind.ACCOUNT_DEACTIVATION);
	}

	@Override
	public void enqueueAdminTransfer(UUID oldAdminID, UUID newAdminID, UUID structureID) {
		enqueue(oldAdminID, oldAdminID, structureID, Kind.ADMIN_REMOVED);
		enqueue(newAdminID, newAdminID, structureID, Kind.ADMIN_GRANTED);
	}

	private void enqueue(UUID recipientID, UUID targetUserID, UUID structureID, Kind kind) {
		jdbcTemplate.update(
				"""
						INSERT INTO account_deactivation_notifications (notification_id, actor_id, target_id, structure_id, kind)
						VALUES (?, ?, ?, ?, ?)
						""",
				UUID.randomUUID(), recipientID, targetUserID, structureID, kind.name());
	}

	@Override
	public Optional<Notification> lockNext() {
		return jdbcTemplate.query("""
				SELECT notification_id, actor_id, target_id, structure_id, kind
				FROM account_deactivation_notifications
				WHERE next_attempt_at <= CURRENT_TIMESTAMP
				ORDER BY next_attempt_at, notification_id
				LIMIT 1 FOR UPDATE SKIP LOCKED
				""", (row, rowNumber) -> new Notification(row.getObject("notification_id", UUID.class),
				row.getObject("actor_id", UUID.class), row.getObject("target_id", UUID.class),
				row.getObject("structure_id", UUID.class), Kind.valueOf(row.getString("kind")))).stream().findFirst();
	}

	@Override
	public void retry(UUID notificationID) {
		jdbcTemplate.update("""
				UPDATE account_deactivation_notifications
				SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes'
				WHERE notification_id = ?
				""", notificationID);
	}

	@Override
	public void remove(UUID notificationID) {
		jdbcTemplate.update("DELETE FROM account_deactivation_notifications WHERE notification_id = ?", notificationID);
	}
}
