package fr.gestionplus.server.account;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.auth.UserRole;

public interface AccountInvitationDeliveryStore {

	void enqueue(Request request);

	Optional<Request> findPending(UUID userID);

	Optional<Request> findById(UUID requestID);

	Optional<Request> findByInvitation(UUID invitationID);

	Optional<Claim> claim(UUID requestID);

	Optional<Request> lockClaim(Claim claim);

	List<UUID> findDue();

	boolean wasSent(UUID requestID);

	boolean complete(Claim claim);

	boolean retry(Claim claim);

	boolean cancel(Claim claim);

	void cancel(UUID requestID);

	record Claim(Request request, int attempt) {
	}

	record Request(UUID requestID, UUID invitationID, UUID previousInvitationID, UUID userID, UUID structureID,
			UUID actorID, long actorVersion, long targetVersion, String email, AuditAction action,
			boolean reintegration, String tokenDigest, Map<UUID, Set<UserRole>> roles) {

		public Request {
			Map<UUID, Set<UserRole>> copy = new HashMap<>();
			roles.forEach((id, values) -> copy.put(id, Set.copyOf(values)));
			roles = Map.copyOf(copy);
		}
	}
}
