package fr.gestionplus.server.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

import jakarta.persistence.LockModeType;

public interface TenantOrganizationJpaRepository extends JpaRepository<TenantOrganizationEntity, UUID> {

	Optional<TenantOrganizationEntity> findBySuperAdminUserID(UUID userID);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT t FROM TenantOrganizationEntity t WHERE t.tenantID = ?1")
	Optional<TenantOrganizationEntity> findByIdForUpdate(UUID tenantID);

	boolean existsBySuperAdminUserID(UUID userID);

	@Modifying(flushAutomatically = true)
	@Query("""
			UPDATE TenantOrganizationEntity tenant SET tenant.superAdminUserID = ?3
			WHERE tenant.tenantID = ?1 AND tenant.superAdminUserID = ?2
			""")
	int replaceSuperAdmin(UUID tenantID, UUID currentUserID, UUID successorUserID);
}
