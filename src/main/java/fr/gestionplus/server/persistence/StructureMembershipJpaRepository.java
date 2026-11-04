package fr.gestionplus.server.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface StructureMembershipJpaRepository
		extends JpaRepository<StructureMembershipEntity, StructureMembershipId> {
}
