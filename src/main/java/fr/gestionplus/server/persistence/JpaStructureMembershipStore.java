package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
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
	}

}
