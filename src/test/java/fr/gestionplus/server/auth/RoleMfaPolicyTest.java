package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RoleMfaPolicyTest {

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "SUPER_ADMIN", "ADMIN", "R_H", "MANAGER", "SECRETARY",
			"SECURITY_OFFICER" })
	void requiresMfaWhenAnyAssignedRoleRequiresIt(UserRole role) {
		assertTrue(RoleMfaPolicy.requiresAny(Set.of(role, UserRole.EMPLOYEE, UserRole.ACCOUNTANT)));
	}

	@Test
	void ordinaryRolesCanLogInWithoutAnEnrolledFactor() {
		assertFalse(RoleMfaPolicy.requiresAny(Set.of(UserRole.EMPLOYEE, UserRole.TEAM_LEAD, UserRole.ACCOUNTANT)));
	}
}
