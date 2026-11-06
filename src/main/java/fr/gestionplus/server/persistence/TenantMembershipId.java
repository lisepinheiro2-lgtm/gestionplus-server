package fr.gestionplus.server.persistence;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public class TenantMembershipId implements Serializable{

	@Column(name = "user_id")
	private UUID userID;
	@Column(name = "tenant_id")
	private UUID tenantID;

	public TenantMembershipId(UUID userID, UUID tenantID) {
		this.userID = Objects.requireNonNull(userID);
		this.tenantID = Objects.requireNonNull(tenantID);
	}

	public TenantMembershipId() {
	}

	public boolean equals(Object obj) {

		if (!(obj instanceof TenantMembershipId))
			return false;

		TenantMembershipId other = (TenantMembershipId) obj;

		return this.tenantID.equals(other.getTenantID()) && this.userID.equals(other.getUserID());
	}

	@Override
	public int hashCode() {
		return Objects.hash(userID, tenantID);
	}

	public UUID getTenantID() {
		return tenantID;
	}

	public UUID getUserID() {
		return userID;
	}
}
