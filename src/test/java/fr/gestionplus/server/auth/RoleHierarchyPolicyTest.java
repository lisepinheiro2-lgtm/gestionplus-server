package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class RoleHierarchyPolicyTest {

	private final RoleHierarchyPolicy policy = new RoleHierarchyPolicy(
			new HttpSecurityConfig().roleHierarchy());

	@ParameterizedTest
	@CsvSource({ "SUPER_ADMIN, ADMIN", "ADMIN, R_H", "ADMIN, ACCOUNTANT", "R_H, MANAGER", "R_H, SECRETARY",
			"MANAGER, TEAM_LEAD", "SECRETARY, TEAM_LEAD", "TEAM_LEAD, EMPLOYEE", "ADMIN, EMPLOYEE", "R_H, EMPLOYEE" })
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

	@ParameterizedTest
	@CsvSource({ "MANAGER, SECRETARY, false", "SECRETARY, MANAGER, false", "SECRETARY, TEAM_LEAD, true",
			"TEAM_LEAD, EMPLOYEE, false", "EMPLOYEE, EMPLOYEE, false", "ACCOUNTANT, EMPLOYEE, false",
			"R_H, ACCOUNTANT, false", "ADMIN, ACCOUNTANT, true", "SUPER_ADMIN, R_H, true" })
	void checksOrdinaryRoleAssignment(UserRole actorRole, UserRole requestedRole, boolean allowed) {
		assertEquals(allowed, policy.canAssignRoles(Set.of(actorRole), Set.of(requestedRole)));
	}

	@Test
	void requiresEveryRequestedRoleToBeAssignable() {
		Set<UserRole> managerRoles = Set.of(UserRole.MANAGER, UserRole.ACCOUNTANT);
		Set<UserRole> superAdminRoles = Set.of(UserRole.SUPER_ADMIN);

		assertFalse(policy.canAssignRoles(managerRoles, Set.of(UserRole.SECRETARY, UserRole.TEAM_LEAD)));
		assertTrue(policy.canAssignRoles(managerRoles, Set.of(UserRole.EMPLOYEE, UserRole.TEAM_LEAD)));
		assertFalse(policy.canAssignRoles(managerRoles, Set.of(UserRole.SECRETARY, UserRole.MANAGER)));
		assertFalse(policy.canAssignRoles(superAdminRoles, Set.of(UserRole.EMPLOYEE, UserRole.ADMIN)));
		assertFalse(policy.canAssignRoles(superAdminRoles, Set.of(UserRole.EMPLOYEE, UserRole.SUPER_ADMIN)));
	}

	@Test
	void combiningRolesDoesNotPermitDelegatingIncomparableRoles() {
		assertFalse(policy.canAssignRoles(Set.of(UserRole.MANAGER, UserRole.R_H), Set.of(UserRole.SECRETARY)));
		assertFalse(policy.canAssignRoles(Set.of(UserRole.SECRETARY, UserRole.ADMIN), Set.of(UserRole.MANAGER)));
		assertFalse(policy.canAssignRoles(Set.of(UserRole.MANAGER, UserRole.SECRETARY), Set.of(UserRole.SECRETARY)));
	}

	@Test
	void rejectsMissingOrNullRolesForAssignment() {
		Set<UserRole> managerRoles = Set.of(UserRole.MANAGER);
		Set<UserRole> secretaryRoles = Set.of(UserRole.SECRETARY);
		Set<UserRole> rolesWithNull = new HashSet<>(managerRoles);
		rolesWithNull.add(null);

		assertFalse(policy.canAssignRoles(null, secretaryRoles));
		assertFalse(policy.canAssignRoles(managerRoles, null));
		assertFalse(policy.canAssignRoles(Set.of(), secretaryRoles));
		assertFalse(policy.canAssignRoles(managerRoles, Set.of()));
		assertFalse(policy.canAssignRoles(rolesWithNull, secretaryRoles));

		rolesWithNull.remove(UserRole.MANAGER);
		rolesWithNull.add(UserRole.SECRETARY);
		assertFalse(policy.canAssignRoles(managerRoles, rolesWithNull));
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
