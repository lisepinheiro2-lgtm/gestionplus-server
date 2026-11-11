package fr.gestionplus.server.organization;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OrganizationalStructureStore {

	Optional<OrganizationalStructure> findById(UUID structureID);

	Optional<OrganizationalStructure> findByIdForUpdate(UUID structureID);

	List<UUID> findIDsByTenant(UUID tenantID);
}
