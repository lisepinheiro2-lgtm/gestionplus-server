package fr.gestionplus.server.organization;

import java.util.Optional;
import java.util.UUID;

public interface StructureMembershipStore {

	Optional<StructureMembership> findByUserAndStructure(UUID userID, UUID structureID);

	void deleteByUserAndStructure(UUID userID, UUID structureID);

	Optional<StructureMembership> findByUserAndStructureForUpdate(UUID userID, UUID structureID);
}
