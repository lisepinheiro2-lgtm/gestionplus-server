package fr.gestionplus.server.persistence;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditTarget;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "audit_events")
public class AuditEventEntity {

	@Id
	@Column(name = "event_id", nullable = false)
	private UUID eventId;

	@Column(name = "occurred_at", nullable = false)
	private Instant occurredAt;

	@Column(name = "actor_user_id")
	private UUID actorUserId;

	@Column(name = "action", nullable = false, length = 100)
	private String action;

	@Column(name = "result", nullable = false, length = 10)
	private String result;

	@Column(name = "ip_address", length = 45)
	private String ipAddress;

	@ElementCollection
	@CollectionTable(name = "audit_targets", joinColumns = @JoinColumn(name = "event_id"))
	private Set<AuditTargetEmbeddable> targets = new HashSet<>();

	protected AuditEventEntity() {
	}

	public AuditEventEntity(AuditEvent event) {
		this.eventId = Objects.requireNonNull(event.getEventId());
		this.occurredAt = Objects.requireNonNull(event.getOccurredAt());
		this.actorUserId = event.getActorUserId();
		this.action = Objects.requireNonNull(event.getAction()).name();
		this.result = Objects.requireNonNull(event.getResult()).name();
		this.ipAddress = event.getIpAddress();

		for (AuditTarget target : event.getTargets()) {

			AuditTargetEmbeddable auditTargetEmbeddable = new AuditTargetEmbeddable(target.getTargetType().name(),
					target.getTargetId());

			targets.add(auditTargetEmbeddable);
		}
	}

	public UUID getEventId() {
		return eventId;
	}

	public String getAction() {
		return action;
	}

	public String getResult() {
		return result;
	}

	public UUID getActorUserId() {
		return actorUserId;
	}

	public String getIpAddress() {
		return ipAddress;
	}

	public Set<AuditTargetEmbeddable> getTargets() {
		return targets;
	}

	public Instant getOccurredAt() {
		return occurredAt;
	}
}
