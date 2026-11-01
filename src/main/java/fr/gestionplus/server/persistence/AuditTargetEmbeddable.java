package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class AuditTargetEmbeddable {

	@Column(name = "target_type", nullable = false, length = 50)
	private String targetType;

	@Column(name = "target_id", nullable = false)
	private UUID targetId;

	public AuditTargetEmbeddable(String targetType, UUID targetId) {
		this.targetType = Objects.requireNonNull(targetType);
		this.targetId = Objects.requireNonNull(targetId);
	}

	@Override
	public boolean equals(Object obj) {

		if (obj == null)
			return false;

		if (!(obj instanceof AuditTargetEmbeddable))
			return false;

		if (obj == this)
			return true;

		AuditTargetEmbeddable other = (AuditTargetEmbeddable) obj;

		if (other.getTargetId().equals(this.getTargetId()) && other.getTargetType().equals(this.getTargetType()))
			return true;

		return false;
	}

	@Override
	public int hashCode() {
		return Objects.hash(targetType, targetId);
	}

	protected AuditTargetEmbeddable() {
	}

	public String getTargetType() {
		return targetType;
	}

	public UUID getTargetId() {
		return targetId;
	}
}
