package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Repository
public class JpaTenantOrganizationStore implements TenantOrganizationStore {

	private final TenantOrganizationJpaRepository repository;

	public JpaTenantOrganizationStore(TenantOrganizationJpaRepository repository) {
		this.repository = Objects.requireNonNull(repository);
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
}
