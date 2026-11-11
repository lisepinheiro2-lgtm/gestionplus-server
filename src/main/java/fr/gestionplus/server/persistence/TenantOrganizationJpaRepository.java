package fr.gestionplus.server.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface TenantOrganizationJpaRepository extends JpaRepository<TenantOrganizationEntity, UUID> {

	Optional<TenantOrganizationEntity> findBySuperAdminUserID(UUID userID);

	boolean existsBySuperAdminUserID(UUID userID);

	@Modifying(flushAutomatically = true)
	@Query("""
			UPDATE TenantOrganizationEntity tenant SET tenant.superAdminUserID = ?3
			WHERE tenant.tenantID = ?1 AND tenant.superAdminUserID = ?2
			""")
	int replaceSuperAdmin(UUID tenantID, UUID currentUserID, UUID successorUserID);
}
