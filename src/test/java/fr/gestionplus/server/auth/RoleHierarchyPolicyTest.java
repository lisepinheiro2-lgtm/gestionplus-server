package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoleHierarchyPolicyTest {

	private final RoleHierarchyPolicy policy = new RoleHierarchyPolicy(
			new HttpSecurityConfig(mock(GestionAuthenticationProvider.class)).roleHierarchy());

	@ParameterizedTest
	@CsvSource({ "SUPER_ADMIN, ADMIN", "ADMIN, R_H", "ADMIN, ACCOUNTANT", "R_H, MANAGER",
			"R_H, SECRETARY", "MANAGER, TEAM_LEAD", "SECRETARY, TEAM_LEAD", "TEAM_LEAD, EMPLOYEE",
			"ADMIN, EMPLOYEE", "R_H, EMPLOYEE" })
	void acceptsStrictlyHigherRole(UserRole actorRole, UserRole targetRole) {
		assertTrue(policy.isStrictlyHigher(Set.of(actorRole), Set.of(targetRole)));
		assertFalse(policy.isStrictlyHigher(Set.of(targetRole), Set.of(actorRole)));
	}

	@Test
	void rejectsEqualRolesAndIncomparableBranches() {
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.ADMIN), Set.of(UserRole.ADMIN)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.MANAGER), Set.of(UserRole.SECRETARY)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.SECRETARY), Set.of(UserRole.MANAGER)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.R_H), Set.of(UserRole.ACCOUNTANT)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.ACCOUNTANT), Set.of(UserRole.EMPLOYEE)));
	}

	@Test
	void requiresEveryTargetRoleToBeStrictlyLower() {
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.R_H), Set.of(UserRole.EMPLOYEE, UserRole.ACCOUNTANT)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.MANAGER), Set.of(UserRole.EMPLOYEE, UserRole.MANAGER)));
		assertTrue(policy.isStrictlyHigher(Set.of(UserRole.ADMIN), Set.of(UserRole.R_H, UserRole.ACCOUNTANT)));
		assertTrue(policy.isStrictlyHigher(Set.of(UserRole.R_H, UserRole.EMPLOYEE),
				Set.of(UserRole.MANAGER, UserRole.SECRETARY)));
	}

	@Test
	void rejectsMissingOrNullRoles() {
		assertThrows(NullPointerException.class, () -> new RoleHierarchyPolicy(null));
		assertFalse(policy.isStrictlyHigher(null, Set.of(UserRole.EMPLOYEE)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.ADMIN), null));
		assertFalse(policy.isStrictlyHigher(Set.of(), Set.of(UserRole.EMPLOYEE)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.ADMIN), Set.of()));

		Set<UserRole> roles = new HashSet<>();
		roles.add(null);

		assertFalse(policy.isStrictlyHigher(roles, Set.of(UserRole.EMPLOYEE)));
		assertFalse(policy.isStrictlyHigher(Set.of(UserRole.ADMIN), roles));
	}
}
