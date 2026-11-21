package fr.gestionplus.server.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

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

	@Override
	public List<UUID> findIDsByTenant(UUID tenantID) {
		return repository.findIDsByTenant(tenantID);
	}

	@Override
	@Transactional(readOnly = true)
	public List<OrganizationalStructure> findCandidatesByUser(UUID userID) {
		Objects.requireNonNull(userID, "L'identifiant du compte ne peut pas être nul.");
		return repository.findCandidatesByUser(userID).stream().map(OrganizationalStructureEntity::toDomain).toList();
	}

}
