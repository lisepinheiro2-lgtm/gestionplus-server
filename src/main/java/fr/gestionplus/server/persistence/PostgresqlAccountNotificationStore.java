package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountNotificationStore;

@Repository
public class PostgresqlAccountNotificationStore implements AccountNotificationStore {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlAccountNotificationStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueueAccountDeactivation(UUID actorUserID, UUID targetUserID, UUID structureID) {
		Objects.requireNonNull(actorUserID, "L’identifiant de l’acteur est obligatoire.");
		Objects.requireNonNull(structureID, "L’identifiant de structure est obligatoire.");
		enqueue(actorUserID, targetUserID, actorUserID, structureID, Kind.ACCOUNT_DEACTIVATION);
		enqueue(actorUserID, targetUserID, targetUserID, structureID, Kind.ACCOUNT_DEACTIVATION_TARGET);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueueAdminTransfer(UUID actorUserID, UUID oldAdminID,
			UUID newAdminID, UUID structureID) {

		Objects.requireNonNull(actorUserID, "L’identifiant de l’acteur est obligatoire.");
		Objects.requireNonNull(newAdminID, "L’identifiant du nouvel ADMIN est obligatoire.");
		Objects.requireNonNull(structureID, "L’identifiant de structure est obligatoire.");
		enqueue(actorUserID, oldAdminID, oldAdminID, structureID, Kind.ADMIN_REMOVED);
		enqueue(actorUserID, newAdminID, newAdminID, structureID, Kind.ADMIN_GRANTED);
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public void enqueueMfaEnrollment(UUID userID) {
		Objects.requireNonNull(userID, "L’identifiant du compte est obligatoire.");
		enqueue(userID, userID, userID, null, Kind.MFA_ENROLLED);
	}

	private void enqueue(UUID actorUserID, UUID targetUserID, UUID recipientUserID, UUID structureID, Kind kind) {
		Notification notification = new Notification(UUID.randomUUID(), actorUserID, targetUserID, recipientUserID,
				structureID, kind, 0);
		jdbcTemplate.update(
				"""
						INSERT INTO account_deactivation_notifications
						    (notification_id, actor_user_id, target_id, recipient_user_id, structure_id, kind)
						VALUES (?, ?, ?, ?, ?, ?)
						""",
				notification.notificationID(), actorUserID, targetUserID, recipientUserID, structureID, kind.name());
	}

	@Override
	public Optional<Notification> lockNext() {
		return jdbcTemplate.query("""
				SELECT notification_id, actor_user_id, target_id, recipient_user_id, structure_id, kind, attempts
				FROM account_deactivation_notifications
				WHERE next_attempt_at <= CURRENT_TIMESTAMP
				ORDER BY next_attempt_at, notification_id
				LIMIT 1 FOR UPDATE SKIP LOCKED
				""", (row, rowNumber) -> new Notification(row.getObject("notification_id", UUID.class),
				row.getObject("actor_user_id", UUID.class), row.getObject("target_id", UUID.class),
				row.getObject("recipient_user_id", UUID.class), row.getObject("structure_id", UUID.class),
				Kind.valueOf(row.getString("kind")), row.getInt("attempts"))).stream().findFirst();
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

	@Override
	public void reserve(UUID notificationID) {
		jdbcTemplate.update("""
				UPDATE account_deactivation_notifications
				SET next_attempt_at = CURRENT_TIMESTAMP + INTERVAL '5 minutes',
				    attempts = attempts + 1
				WHERE notification_id = ?
				""", notificationID);
	}
}
