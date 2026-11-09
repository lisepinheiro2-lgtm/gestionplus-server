package fr.gestionplus.server.persistence;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;

@Repository
public class JpaStructureMembershipStore implements StructureMembershipStore {

	private final StructureMembershipJpaRepository repository;

	public JpaStructureMembershipStore(StructureMembershipJpaRepository respository) {
		this.repository = Objects.requireNonNull(respository);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<StructureMembership> findByUserAndStructure(UUID userID, UUID structureID) {

		StructureMembershipId id = new StructureMembershipId(userID, structureID);
		Optional<StructureMembershipEntity> entity = repository.findById(id);

		return entity.map(account -> account.toDomain());
	}

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
		StructureMembershipEntity entity = new StructureMembershipEntity(structureMembership);
		repository.save(entity);
	}

	@Override
	public void saveAndFlush(StructureMembership structureMembership) {
		repository.saveAndFlush(new StructureMembershipEntity(structureMembership));
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
