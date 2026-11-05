package fr.gestionplus.server.organization;

import java.util.Optional;
import java.util.UUID;

public interface TenantOrganizationStore {

	Optional<TenantOrganization> findById(UUID tenantID);
}
