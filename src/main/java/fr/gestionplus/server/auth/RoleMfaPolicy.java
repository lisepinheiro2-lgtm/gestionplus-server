package fr.gestionplus.server.auth;

import java.util.Set;

public final class RoleMfaPolicy {

	private static final Set<UserRole> REQUIRED = Set.of(UserRole.SUPER_ADMIN, UserRole.ADMIN, UserRole.R_H,
			UserRole.MANAGER, UserRole.SECRETARY, UserRole.SECURITY_OFFICER);

	private RoleMfaPolicy() {
	}

	public static boolean requiresAny(Set<UserRole> roles) {
		return roles.stream().anyMatch(REQUIRED::contains);
	}
}
