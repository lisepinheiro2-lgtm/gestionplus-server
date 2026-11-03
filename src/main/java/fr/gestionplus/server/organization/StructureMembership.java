package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.auth.UserRole;

public class StructureMembership {

	private final UUID userID;
	private final UUID structureID;
	private final Set<UserRole> roles;

	public StructureMembership(UUID userID, UUID structureID, Set<UserRole> roles) {
		this.userID = Objects.requireNonNull(userID);
		this.structureID = Objects.requireNonNull(structureID);
		this.roles = Set.copyOf(roles);
	}

	public UUID getUserID() {
		return userID;
	}

	public UUID getStructureID() {
		return structureID;
	}

	public Set<UserRole> getRoles() {
		return roles;
	}
}
