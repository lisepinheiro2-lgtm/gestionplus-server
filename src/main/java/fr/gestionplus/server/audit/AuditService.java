package fr.gestionplus.server.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class AuditService {

	private final AuditStore auditStore;
	private final AuditContextProvider contextProvider;

	public AuditService(AuditStore auditStore, AuditContextProvider contextProvider) {
		this.auditStore = Objects.requireNonNull(auditStore);
		this.contextProvider = Objects.requireNonNull(contextProvider);
	}

	public void record(AuditAction action, AuditResult result, Set<AuditTarget> targets) {

		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.now();
		UUID actorUserId = contextProvider.getActorUserId();
		String actorUserIp = contextProvider.getIpAddress();
		
		AuditEvent event = new AuditEvent(occurredAt, eventId, actorUserId, action, result, actorUserIp, targets);
		auditStore.save(event);
	}
}
