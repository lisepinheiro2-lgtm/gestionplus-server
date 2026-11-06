package fr.gestionplus.server.persistence;

import java.util.Objects;

import fr.gestionplus.server.organization.TenantMembership;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenant_memberships")
public class TenantMembershipEntity {

	@EmbeddedId
	private TenantMembershipId id;

	protected TenantMembershipEntity() {
	}

	public TenantMembershipEntity(TenantMembership tenantMembership) {
		Objects.requireNonNull(tenantMembership);
		this.id = new TenantMembershipId(tenantMembership.getUserID(), tenantMembership.getTenantID());
	}

	public TenantMembership toDomain() {
		return new TenantMembership(id.getUserID(), id.getTenantID());
	}
}
