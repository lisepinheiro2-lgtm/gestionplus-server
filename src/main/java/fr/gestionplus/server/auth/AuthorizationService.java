package fr.gestionplus.server.auth;

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
}
