package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Repository
public class JpaTenantOrganizationStore implements TenantOrganizationStore {

	private final TenantOrganizationJpaRepository repository;
	private final JdbcTemplate jdbcTemplate;

	public JpaTenantOrganizationStore(TenantOrganizationJpaRepository repository, JdbcTemplate jdbcTemplate) {
		this.repository = Objects.requireNonNull(repository);
		this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate);
	}

	@Override
	public Optional<TenantOrganization> findById(UUID tenantID) {
		return repository.findById(tenantID).map(TenantOrganizationEntity::toDomain);
	}

	@Override
	public Optional<TenantOrganization> findBySuperAdminUserID(UUID userID) {
		return repository.findBySuperAdminUserID(userID).map(TenantOrganizationEntity::toDomain);
	}

	@Override
	public boolean replaceSuperAdmin(UUID tenantID, UUID currentUserID, UUID successorUserID) {
		return repository.replaceSuperAdmin(tenantID, currentUserID, successorUserID) == 1;
	}

	@Override
	@Transactional(propagation = Propagation.MANDATORY)
	public Optional<TenantOrganization> findByIdForUpdate(UUID tenantID) {
		return jdbcTemplate.query("""
				SELECT tenant_id, tenant_name, super_admin_user_id
				FROM tenant_organizations WHERE tenant_id = ? FOR UPDATE
				""", (row, number) -> new TenantOrganization(row.getObject("tenant_id", UUID.class),
				row.getString("tenant_name"), row.getObject("super_admin_user_id", UUID.class)), tenantID)
				.stream().findFirst();
	}
}
