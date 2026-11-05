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
		return repository.findById(tenantID).map(entity -> entity.toDomain());
	}
}
