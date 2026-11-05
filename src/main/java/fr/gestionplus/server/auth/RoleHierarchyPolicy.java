package fr.gestionplus.server.auth;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.stereotype.Component;

@Component
public class RoleHierarchyPolicy {

	private final RoleHierarchy roleHierarchy;

	public RoleHierarchyPolicy(RoleHierarchy roleHierarchy) {
		this.roleHierarchy = Objects.requireNonNull(roleHierarchy);
	}

	public boolean isStrictlyHigher(Set<UserRole> actorRoles, Set<UserRole> targetRoles) {

		if (actorRoles == null || targetRoles == null || actorRoles.isEmpty() || targetRoles.isEmpty()) {
			return false;
		}

		for (UserRole role : actorRoles) {
			if (role == null) {
				return false;
			}
		}

		for (UserRole role : targetRoles) {
			if (role == null) {
				return false;
			}
		}

		for (UserRole targetRole : targetRoles) {

			boolean isHigher = false;

			SimpleGrantedAuthority targetAuthority = new SimpleGrantedAuthority("ROLE_" + targetRole.name());

			for (UserRole actorRole : actorRoles) {

				if (actorRole == targetRole) {
					continue;
				}

				var reachableRoles = roleHierarchy.getReachableGrantedAuthorities(
						List.of(new SimpleGrantedAuthority("ROLE_" + actorRole.name())));

				if (reachableRoles.contains(targetAuthority)) {
					isHigher = true;
					break;
				}
			}

			if (!isHigher) {
				return false;
			}
		}

		return true;
	}

}
