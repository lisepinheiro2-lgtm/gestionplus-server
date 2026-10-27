package fr.gestionplus.server.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface OrganizationalUnitJpaRepository extends JpaRepository<OrganizationalUnitEntity, UUID> {

}
