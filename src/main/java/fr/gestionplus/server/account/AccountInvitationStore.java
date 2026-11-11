package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AccountInvitationStore {

	Optional<Invitation> findByAccount(UUID userID);

	Optional<Invitation> findByID(UUID invitationID);

	List<UUID> findExpired(Instant now);

	void save(Invitation invitation);

	void removeForAccount(UUID userID);

	record Invitation(UUID invitationID, UUID userID, UUID structureID, String tokenDigest, Instant expiresAt,
			Instant completedAt, boolean reintegration) {
		public Invitation(UUID invitationID, UUID userID, UUID structureID, String tokenDigest, Instant expiresAt,
				Instant completedAt) {
			this(invitationID, userID, structureID, tokenDigest, expiresAt, completedAt, false);
		}
	}
}
