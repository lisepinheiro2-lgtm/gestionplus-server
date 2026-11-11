package fr.gestionplus.server.organization;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.auth.UserRole;

public interface StructureMembershipStore {

	Optional<StructureMembership> findByUserAndStructure(UUID userID, UUID structureID);

	List<StructureMembership> findByUser(UUID userID);

	Set<UserRole> findEffectiveLocalRoles(UUID userID);

	List<UUID> findStructureIDsByUser(UUID userID);

	List<StructureMembership> findByUserAndTenant(UUID userID, UUID tenantID);

	void deleteByUserAndStructure(UUID userID, UUID structureID);

	Optional<StructureMembership> findByUserAndStructureForUpdate(UUID userID, UUID structureID);

	void save(StructureMembership structureMembership);

	void saveAndFlush(StructureMembership structureMembership);

	Optional<StructureMembership> findAdminByStructure(UUID structureID);

	List<StructureMembership> findAdminMembershipsByUserAndTenant(UUID userID, UUID tenantID);
}
