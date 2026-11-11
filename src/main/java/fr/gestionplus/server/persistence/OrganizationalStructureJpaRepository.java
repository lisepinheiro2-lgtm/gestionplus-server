package fr.gestionplus.server.persistence;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface OrganizationalStructureJpaRepository extends JpaRepository<OrganizationalStructureEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM OrganizationalStructureEntity s WHERE s.structureID = ?1")
	Optional<OrganizationalStructureEntity> findByIdForUpdate(UUID structureID);

	@Query("SELECT s.structureID FROM OrganizationalStructureEntity s WHERE s.tenantID = ?1 ORDER BY s.structureID")
	List<UUID> findIDsByTenant(UUID tenantID);
}
