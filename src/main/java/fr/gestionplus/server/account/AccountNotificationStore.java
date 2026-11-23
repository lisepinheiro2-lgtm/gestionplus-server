package fr.gestionplus.server.account;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public interface AccountNotificationStore {

	void enqueueAccountDeactivation(UUID actorUserID, UUID targetUserID, UUID structureID);

	void enqueueAdminTransfer(UUID actorUserID, UUID oldAdminID, UUID newAdminID, UUID structureID);

	void enqueueMfaEnrollment(UUID userID);

	Optional<Notification> lockNext();

	void reserve(UUID notificationID);

	void retry(UUID notificationID);

	void remove(UUID notificationID);

	enum Kind {
		ACCOUNT_DEACTIVATION, ADMIN_REMOVED, ADMIN_GRANTED, MFA_ENROLLED, ACCOUNT_DEACTIVATION_TARGET
	}

	record Notification(UUID notificationID, UUID actorUserID, UUID targetUserID, UUID recipientUserID,
			UUID structureID, Kind kind,
			int attempts) {

		public Notification(UUID notificationID, UUID actorUserID, UUID targetUserID, UUID recipientUserID,
				UUID structureID) {
			this(notificationID, actorUserID, targetUserID, recipientUserID, structureID, Kind.ACCOUNT_DEACTIVATION, 0);
		}

		public Notification {
			Objects.requireNonNull(kind, "Le type de notification est obligatoire.");
			Objects.requireNonNull(targetUserID, "L'identifiant du compte concerné est obligatoire.");
			Objects.requireNonNull(recipientUserID, "L'identifiant du destinataire est obligatoire.");
			Objects.requireNonNull(notificationID, "L'identifiant de la notification concernée est obligatoire.");

			if (attempts < 0) {
				throw new IllegalArgumentException("Le nombre de tentatives ne peut pas être négatif.");
			}

			switch (kind) {

			case MFA_ENROLLED -> {

				if (structureID != null) {
					throw new IllegalArgumentException("L'identifiant de structure doit être null.");
				}

				if (!targetUserID.equals(recipientUserID) || !targetUserID.equals(actorUserID)) {
					throw new IllegalArgumentException(
							"Pour un enrôlement MFA, l'acteur, le compte concerné et le destinataire doivent être identiques.");
				}

			}
			default -> {

				if (structureID == null) {
					throw new IllegalArgumentException("L'identifiant de structure est obligatoire.");
				}

			}
			}

		}
	}
}
