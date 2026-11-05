package fr.gestionplus.server.persistence;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface StructureMembershipJpaRepository
		extends JpaRepository<StructureMembershipEntity, StructureMembershipId> {
	
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT m FROM StructureMembershipEntity m WHERE m.id = ?1")
	Optional<StructureMembershipEntity> findByIdForUpdate(StructureMembershipId id);
}
