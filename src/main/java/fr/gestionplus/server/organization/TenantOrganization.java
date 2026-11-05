package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.UUID;

public class TenantOrganization {

	private final UUID tenantID;
	private final String tenantName;
	private final UUID superAdminUserID;

	public TenantOrganization(UUID tenantID, String tenantName, UUID superAdminUserID) {
		this.tenantID = Objects.requireNonNull(tenantID);
		this.tenantName = Objects.requireNonNull(tenantName).strip();
		this.superAdminUserID = Objects.requireNonNull(superAdminUserID);

		if (tenantName.isBlank())
			throw new IllegalArgumentException("Le nom ne peut pas être vide ou blanc.");
	}

	public UUID getTenantID() {
		return tenantID;
	}

	public String getTenantName() {
		return tenantName;
	}

	public UUID getSuperAdminUserID() {
		return superAdminUserID;
	}
}
