package fr.gestionplus.server.audit;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public class AuditEvent {

	private final UUID eventId;
	private final Instant occurredAt;
	private final UUID actorUserId;
	private final AuditAction action;
	private final AuditResult result;
	private final String ipAddress;
	private final Set<AuditTarget> targets;
	private final AuditMetadata metadata;
	private final UUID tenantId;

	public AuditEvent(Instant occurredAt, UUID eventId, UUID actorUserId, AuditAction action, AuditResult result,
			String ipAddress, Set<AuditTarget> targets) {

		this(occurredAt, eventId, actorUserId, action, result, ipAddress, targets, AuditMetadata.empty());
	}

	public AuditEvent(Instant occurredAt, UUID eventId, UUID actorUserId, AuditAction action, AuditResult result,
			String ipAddress, Set<AuditTarget> targets, AuditMetadata metadata) {

		this(occurredAt, eventId, actorUserId, action, result, ipAddress, targets, metadata, null);
	}

	public AuditEvent(Instant occurredAt, UUID eventId, UUID actorUserId, AuditAction action, AuditResult result,
			String ipAddress, Set<AuditTarget> targets, AuditMetadata metadata, UUID tenantId) {

		this.tenantId = tenantId;
		this.eventId = Objects.requireNonNull(eventId);
		this.occurredAt = Objects.requireNonNull(occurredAt);
		this.action = Objects.requireNonNull(action);
		this.result = Objects.requireNonNull(result);
		this.actorUserId = actorUserId;
		this.ipAddress = ipAddress;
		this.targets = Set.copyOf(targets);
		this.metadata = Objects.requireNonNull(metadata);
	}

	public UUID getEventId() {
		return eventId;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public AuditAction getAction() {
		return action;
	}

	public AuditResult getResult() {
		return result;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public Set<AuditTarget> getTargets() {
		return targets;
	}

	public UUID getTenantId() {
		return tenantId;
	}

	public AuditMetadata getMetadata() {
		return metadata;
	}
}
