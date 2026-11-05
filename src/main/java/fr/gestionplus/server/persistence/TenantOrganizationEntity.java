package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.UUID;

import fr.gestionplus.server.organization.TenantOrganization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "tenant_organizations")
public class TenantOrganizationEntity {

	@Id
	@Column(name = "tenant_id", nullable = false)
	private UUID tenantID;

	@Column(name = "tenant_name", nullable = false)
	private String tenantName;

	@Column(name = "super_admin_user_id", nullable = false)
	private UUID superAdminUserID;

	protected TenantOrganizationEntity() {
	}

	public TenantOrganizationEntity(TenantOrganization tenantOrganization) {

		Objects.requireNonNull(tenantOrganization);
		this.tenantID = tenantOrganization.getTenantID();
		this.tenantName = tenantOrganization.getTenantName();
		this.superAdminUserID = tenantOrganization.getSuperAdminUserID();
	}

	public TenantOrganization toDomain() {
		return new TenantOrganization(tenantID, tenantName, superAdminUserID);
	}
}
