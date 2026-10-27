package fr.gestionplus.server.audit;

import java.util.Objects;
import java.util.UUID;

public final class AuditTarget {

	private final AuditTargetType targetType;
	private final UUID targetId;
	

	public AuditTarget(AuditTargetType targetType, UUID targetId) {
		this.targetType = Objects.requireNonNull(targetType);
		this.targetId = Objects.requireNonNull(targetId);
	}

	@Override
	public boolean equals(Object obj) {

		if (obj == null)
			return false;

		if (!(obj instanceof AuditTarget))
			return false;

		if (obj == this)
			return true;

		AuditTarget other = (AuditTarget) obj;

		if (other.getTargetId().equals(this.getTargetId()) && other.getTargetType().equals(this.getTargetType()))
			return true;

		return false;
	}

	@Override
	public int hashCode() {

		return Objects.hash(targetType, targetId);
	}

	public AuditTargetType getTargetType() {
		return targetType;
	}

	public UUID getTargetId() {
		return targetId;
	}
}
