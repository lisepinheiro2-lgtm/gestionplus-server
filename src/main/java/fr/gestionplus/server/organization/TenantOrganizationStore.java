package fr.gestionplus.server.organization;

import java.util.Optional;
import java.util.UUID;

public interface TenantOrganizationStore {

	Optional<TenantOrganization> findById(UUID tenantID);

	Optional<TenantOrganization> findBySuperAdminUserID(UUID userID);

	Optional<TenantOrganization> findByIdForUpdate(UUID tenantID);

	boolean replaceSuperAdmin(UUID tenantID, UUID currentUserID, UUID successorUserID);
}
