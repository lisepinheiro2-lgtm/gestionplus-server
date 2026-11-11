package fr.gestionplus.server.account;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;

public record PrivilegedAccountSnapshot(UUID userID, UserAccountStatus status, long sessionVersion,
		List<LocalRoles> memberships) {

	public PrivilegedAccountSnapshot {
		memberships = memberships.stream().sorted(Comparator.comparing(LocalRoles::structureID)).toList();
	}

	static PrivilegedAccountSnapshot capture(UserAccount account, List<StructureMembership> memberships) {
		return new PrivilegedAccountSnapshot(account.getUserID(), account.getStatu(), account.getSessionVersion(),
				memberships.stream().map(membership -> new LocalRoles(membership.getStructureID(),
						membership.getRoles().stream().sorted().toList())).toList());
	}

	Set<UserRole> rolesIn(UUID structureID) {
		return memberships.stream().filter(membership -> structureID.equals(membership.structureID()))
				.findFirst().map(membership -> Set.copyOf(membership.roles())).orElse(Set.of());
	}

	public record LocalRoles(UUID structureID, List<UserRole> roles) {

		public LocalRoles { roles = roles.stream().sorted().toList(); }
	}
}
