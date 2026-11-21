package fr.gestionplus.server.organization;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserRole;

@Service
public class OrganizationalStructureQueryService {

	private final StructureMembershipStore membershipStore;
	private final OrganizationalStructureStore structureStore;
	private final AuthorizationService authorizationService;

	public OrganizationalStructureQueryService(StructureMembershipStore membershipStore,
			OrganizationalStructureStore structureStore, AuthorizationService authorizationService) {
		this.membershipStore = Objects.requireNonNull(membershipStore);
		this.structureStore = Objects.requireNonNull(structureStore);
		this.authorizationService = Objects.requireNonNull(authorizationService);
	}

	public List<OrganizationalStructure> listAccessibleStructures(UserAccount principal) {

		List<UUID> structuresID = membershipStore.findStructureIDsByUser(principal.getUserID());
		List<OrganizationalStructure> structures = new ArrayList<>();

		for (UUID structureID : structuresID) {
			Optional<OrganizationalStructure> structureOptional = structureStore.findById(structureID);
			Set<UserRole> roles = authorizationService.rolesInStructureForSession(principal, structureID);

			if (structureOptional.isPresent() && !roles.isEmpty()) {
				structures.add(structureOptional.get());
			}

		}

		structures.sort(Comparator.comparing(OrganizationalStructure::getStructureName)
				.thenComparing(OrganizationalStructure::getStructureID));
		return structures;
	}

	public Optional<OrganizationalStructure> getAccessibleStructure(UserAccount principal, UUID structureID) {

		Set<UserRole> roles = authorizationService.rolesInStructureForSession(principal, structureID);

		if (roles.isEmpty()) {
			return Optional.empty();
		}

		Optional<OrganizationalStructure> structure = structureStore.findById(structureID);

		if (structure.isPresent()) {
			return structure;
		}

		return Optional.empty();
	}

}
