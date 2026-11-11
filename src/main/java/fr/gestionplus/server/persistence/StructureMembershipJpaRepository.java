package fr.gestionplus.server.persistence;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import fr.gestionplus.server.auth.UserRole;
import jakarta.persistence.LockModeType;

public interface StructureMembershipJpaRepository
		extends JpaRepository<StructureMembershipEntity, StructureMembershipId> {

	@EntityGraph(attributePaths = "roles")
	@Query("SELECT m FROM StructureMembershipEntity m WHERE m.id.userID = ?1")
	List<StructureMembershipEntity> findByUserID(UUID userID);

	@Query("""
			SELECT DISTINCT r FROM StructureMembershipEntity membership JOIN membership.roles r
			WHERE membership.id.userID = ?1
			AND EXISTS (
				SELECT 1 FROM TenantMembershipEntity tenant
				WHERE tenant.id.userID = ?1 AND tenant.id.tenantID = membership.tenantID
			)
			""")
	Set<UserRole> findEffectiveLocalRoles(UUID userID);

	@Query("SELECT m.id.structureID FROM StructureMembershipEntity m WHERE m.id.userID = ?1")
	List<UUID> findStructureIDsByUser(UUID userID);

	@EntityGraph(attributePaths = "roles")
	@Query("SELECT m FROM StructureMembershipEntity m WHERE m.id.userID = ?1 AND m.tenantID = ?2")
	List<StructureMembershipEntity> findByUserAndTenant(UUID userID, UUID tenantID);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT m FROM StructureMembershipEntity m WHERE m.id = ?1")
	Optional<StructureMembershipEntity> findByIdForUpdate(StructureMembershipId id);

	@Query("SELECT m FROM StructureMembershipEntity m JOIN m.roles r " + "WHERE m.id.structureID = ?1 AND r = ?2")
	Optional<StructureMembershipEntity> findByStructureAndRole(UUID structureID, UserRole role);

	@Query("SELECT m FROM StructureMembershipEntity m "
			+ "JOIN OrganizationalStructureEntity s ON m.id.structureID = s.structureID " + "JOIN m.roles r "
			+ "WHERE m.id.userID = ?1 " + "AND s.tenantID = ?2 " + "AND r = ?3")
	List<StructureMembershipEntity> findByUserAndTenantAndRole(UUID userID, UUID tenantID, UserRole role);
}
