package fr.gestionplus.server.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;

@Repository
public class JpaStructureMembershipStore implements StructureMembershipStore {

	private final StructureMembershipJpaRepository repository;
	private final OrganizationalStructureJpaRepository structureRepository;

	public JpaStructureMembershipStore(StructureMembershipJpaRepository repository,
			OrganizationalStructureJpaRepository structureRepository) {
		this.repository = Objects.requireNonNull(repository);
		this.structureRepository = Objects.requireNonNull(structureRepository);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StructureMembership> findByUserAndStructure(UUID userID, UUID structureID) {

		StructureMembershipId id = new StructureMembershipId(userID, structureID);
		Optional<StructureMembershipEntity> entity = repository.findById(id);

		return entity.map(account -> account.toDomain());
	}

	@Override
	public void deleteByUserAndStructure(UUID userID, UUID structureID) {
		StructureMembershipId structureMembershipID = new StructureMembershipId(userID, structureID);
		repository.deleteById(structureMembershipID);
		repository.flush();
	}

	@Override
	public Optional<StructureMembership> findByUserAndStructureForUpdate(UUID userID, UUID structureID) {
		StructureMembershipId id = new StructureMembershipId(userID, structureID);
		return repository.findByIdForUpdate(id).map(structure -> structure.toDomain());
	}

	@Override
	public void save(StructureMembership structureMembership) {
		repository.save(toEntity(structureMembership));
	}

	@Override
	public void saveAndFlush(StructureMembership structureMembership) {
		repository.saveAndFlush(toEntity(structureMembership));
	}

	private StructureMembershipEntity toEntity(StructureMembership membership) {
		UUID tenantID = structureRepository.findById(membership.getStructureID())
				.orElseThrow().toDomain().getTenantID();
		return new StructureMembershipEntity(membership, tenantID);
	}

	@Override
	@Transactional(readOnly = true)
	public List<StructureMembership> findByUser(UUID userID) {
		return repository.findByUserID(userID).stream().map(StructureMembershipEntity::toDomain).toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Set<UserRole> findEffectiveLocalRoles(UUID userID) {
		return repository.findEffectiveLocalRoles(userID);
	}

	@Override
	@Transactional(readOnly = true)
	public List<UUID> findStructureIDsByUser(UUID userID) {
		return repository.findStructureIDsByUser(userID);
	}

	@Override
	@Transactional(readOnly = true)
	public List<StructureMembership> findByUserAndTenant(UUID userID, UUID tenantID) {
		return repository.findByUserAndTenant(userID, tenantID).stream().map(StructureMembershipEntity::toDomain)
				.toList();
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StructureMembership> findAdminByStructure(UUID structureID) {
		return repository.findByStructureAndRole(structureID, UserRole.ADMIN).map(structure -> structure.toDomain());
	}

	@Override
	@Transactional(readOnly = true)
	public List<StructureMembership> findAdminMembershipsByUserAndTenant(UUID userID, UUID tenantID) {

		List<StructureMembershipEntity> membershipEntitys = repository.findByUserAndTenantAndRole(userID, tenantID,
				UserRole.ADMIN);

		List<StructureMembership> structureMemberships = new ArrayList<>();

		for (StructureMembershipEntity membershipEntity : membershipEntitys) {
			structureMemberships.add(membershipEntity.toDomain());
		}

		return structureMemberships;
	}

}
