package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.organization.TenantMembership;
import fr.gestionplus.server.organization.TenantMembershipStore;

@Repository
public class JpaTenantMembershipStore implements TenantMembershipStore {

	private final TenantMembershipJpaRepository repository;

	public JpaTenantMembershipStore(TenantMembershipJpaRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<TenantMembership> findByUserAndTenant(UUID userID, UUID tenantID) {

		TenantMembershipId id = new TenantMembershipId(userID, tenantID);
		Optional<TenantMembershipEntity> entity = repository.findById(id);

		return entity.map(membership -> membership.toDomain());
	}

	@Override
	public void save(TenantMembership tenantMembership) {
		TenantMembershipEntity entity = new TenantMembershipEntity(tenantMembership);
		repository.save(entity);
	}
}
