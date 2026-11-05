package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;

@Repository
public class JpaOrganizationalStructureStore implements OrganizationalStructureStore {

	private final OrganizationalStructureJpaRepository repository;

	public JpaOrganizationalStructureStore(OrganizationalStructureJpaRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	@Override
	public Optional<OrganizationalStructure> findById(UUID structureID) {
		return repository.findById(structureID).map(entity -> entity.toDomain());
	}

	@Override
	public Optional<OrganizationalStructure> findByIdForUpdate(UUID structureID) {
		return repository.findByIdForUpdate(structureID).map(entity -> entity.toDomain());
	}

}
