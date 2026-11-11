package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.UUID;

public class TenantMembership {

	private final UUID userID;
	private final UUID tenantID;

	public TenantMembership(UUID userID, UUID tenantID) {
		this.userID = Objects.requireNonNull(userID);
		this.tenantID = Objects.requireNonNull(tenantID);
	}

	public UUID getUserID() {
		return userID;
	}

	public UUID getTenantID() {
		return tenantID;
	}
}
