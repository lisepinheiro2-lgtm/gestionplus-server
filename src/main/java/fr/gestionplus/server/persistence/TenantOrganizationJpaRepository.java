package fr.gestionplus.server.persistence;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface TenantOrganizationJpaRepository extends JpaRepository<TenantOrganizationEntity, UUID>{

}
