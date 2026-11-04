package fr.gestionplus.server.auth;

import java.util.UUID;

import fr.gestionplus.server.organization.StructureMembership;

public class AuthorizationService {

	public static boolean can(UserAccount account, Permission permission) {

		if (account == null || permission == null || account.getStatu() != UserAccountStatus.ACTIVE)
			return false;

		for (UserRole role : account.getRoles()) {

			if (RolePermissionPolicy.allows(role, permission)) {
				return true;
			}
		}

		return false;
	}

	public static boolean canInStructure(UserAccount account, StructureMembership membership, UUID structureID,
			Permission permission) {

		if (account == null || membership == null || structureID == null || permission == null)
			return false;

		if (account.getStatu() != UserAccountStatus.ACTIVE)
			return false;

		if (!membership.getUserID().equals(account.getUserID()))
			return false;

		if (!membership.getStructureID().equals(structureID))
			return false;

		for (UserRole role : membership.getRoles()) {

			if (RolePermissionPolicy.allows(role, permission))
				return true;
		}

		return false;
	}
}
