package fr.gestionplus.server.organization;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationalStructureStore {

	Optional<OrganizationalStructure> findById(UUID structureID);
	
	Optional<OrganizationalStructure> findByIdForUpdate(UUID structureID);
}
