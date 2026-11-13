package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

public interface AccountDeactivationNotifications {

	void enqueue(UUID actorUserID, UUID targetUserID, UUID structureID);

	void enqueueAdminTransfer(UUID oldAdminID, UUID newAdminID, UUID structureID);

	Optional<Notification> lockNext();

	void reserve(UUID notificationID);

	void retry(UUID notificationID);

	void remove(UUID notificationID);

	enum Kind {
		ACCOUNT_DEACTIVATION, ADMIN_REMOVED, ADMIN_GRANTED
	}

	record Notification(UUID notificationID, UUID actorUserID, UUID targetUserID, UUID structureID, Kind kind,
			int attempts) {

		public Notification(UUID notificationID, UUID actorUserID, UUID targetUserID, UUID structureID) {
			this(notificationID, actorUserID, targetUserID, structureID, Kind.ACCOUNT_DEACTIVATION, 0);
		}
	}
}
