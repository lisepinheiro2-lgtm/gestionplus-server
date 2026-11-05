package fr.gestionplus.server.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface OrganizationalStructureJpaRepository extends JpaRepository<OrganizationalStructureEntity, UUID>{

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT s FROM OrganizationalStructureEntity s WHERE s.structureID = ?1")
	Optional<OrganizationalStructureEntity> findByIdForUpdate(UUID structureID);
}
