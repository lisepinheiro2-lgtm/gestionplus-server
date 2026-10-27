package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import fr.gestionplus.server.organization.OrganizationalUnit;
import fr.gestionplus.server.organization.OrganizationalUnitStore;

@Repository
public class JpaOrganizationalUnitStore implements OrganizationalUnitStore {

	private final OrganizationalUnitJpaRepository repository;

	public JpaOrganizationalUnitStore(OrganizationalUnitJpaRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	@Override
	public void createUnit(OrganizationalUnit unit) {
		OrganizationalUnitEntity entity = new OrganizationalUnitEntity(unit);
		repository.save(entity);
	}

	@Override
	public Optional<OrganizationalUnit> findById(UUID unitId) {
		return repository.findById(unitId).map(entity -> entity.toDomain());
	}

}
