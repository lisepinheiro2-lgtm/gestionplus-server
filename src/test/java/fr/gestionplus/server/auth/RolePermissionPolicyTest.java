package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class RolePermissionPolicyTest {

	private static final Set<String> REVIEWED_PERMISSIONS = Set.of(
			"INVITE_USERS", "MANAGE_USERS", "MANAGE_USER_ROLES", "CREATE_CONTRACT", "EDIT_CONTRACT",
			"VIEW_OWN_SCHEDULE", "VIEW_TEAM_SCHEDULE", "CREATE_INTERVENTION", "EDIT_INTERVENTION",
			"CANCEL_INTERVENTION", "ASSIGN_INTERVENTION", "MANAGE_ABSENCES", "MANAGE_REPLACEMENTS",
			"CREATE_CLIENT", "VIEW_CLIENT_PUBLIC_PROFILE", "VIEW_CLIENT_PRIVATE_PROFILE",
			"VIEW_ASSIGNED_CLIENT_PUBLIC_PROFILE", "EDIT_CLIENT_PUBLIC_PROFILE", "EDIT_CLIENT_PRIVATE_PROFILE",
			"ARCHIVE_CLIENT", "VIEW_ARCHIVED_CLIENTS", "RESTORE_CLIENT", "CREATE_EMPLOYEE",
			"VIEW_EMPLOYEE_PROFESSIONAL_PROFILE", "EDIT_EMPLOYEE_PROFESSIONAL_PROFILE",
			"VIEW_EMPLOYEE_PRIVATE_PROFILE", "EDIT_EMPLOYEE_PRIVATE_PROFILE", "ARCHIVE_EMPLOYEE",
			"VIEW_ARCHIVED_EMPLOYEES", "RESTORE_EMPLOYEE", "CREATE_DOCUMENT", "VIEW_DOCUMENT", "EDIT_DOCUMENT",
			"ARCHIVE_DOCUMENT", "VIEW_ARCHIVED_DOCUMENTS", "RESTORE_DOCUMENT", "UPLOAD_SENSITIVE_DOCUMENT",
			"VIEW_SENSITIVE_DOCUMENT", "EDIT_SENSITIVE_DOCUMENT", "VIEW_ACCOUNTING", "CREATE_INVOICE",
			"EDIT_DRAFT_INVOICE", "ISSUE_INVOICE", "ISSUE_CREDIT_NOTE", "PREPARE_PAYROLL", "MANAGE_PAYROLL",
			"VIEW_OWN_PAYSLIP");

	@Test
	void addingAPermissionRequiresAnExplicitSecurityReview() {
		Set<String> declared = Arrays.stream(Permission.values()).map(Enum::name).collect(Collectors.toSet());

		assertEquals(REVIEWED_PERMISSIONS, declared,
				"Toute nouvelle permission exige une revue explicite de la matrice des rôles.");
	}

	@ParameterizedTest
	@EnumSource(value = UserRole.class, names = { "SUPER_ADMIN", "ADMIN" })
	void privilegedRolesReceiveExactlyTheReviewedPermissions(UserRole role) {
		assertEquals(REVIEWED_PERMISSIONS, permissions(role));
	}

	@Test
	void secretaryHasOnlyTheCurrentlyDefinedPermissions() {
		assertEquals(Set.of("INVITE_USERS", "MANAGE_USERS", "MANAGE_USER_ROLES", "CREATE_CONTRACT", "EDIT_CONTRACT",
				"VIEW_TEAM_SCHEDULE", "CREATE_INTERVENTION", "EDIT_INTERVENTION", "ASSIGN_INTERVENTION",
				"MANAGE_REPLACEMENTS", "MANAGE_ABSENCES", "CANCEL_INTERVENTION", "CREATE_CLIENT",
				"VIEW_CLIENT_PUBLIC_PROFILE", "EDIT_CLIENT_PUBLIC_PROFILE", "CREATE_EMPLOYEE",
				"VIEW_EMPLOYEE_PROFESSIONAL_PROFILE", "EDIT_EMPLOYEE_PROFESSIONAL_PROFILE", "CREATE_DOCUMENT",
				"VIEW_DOCUMENT", "EDIT_DOCUMENT", "UPLOAD_SENSITIVE_DOCUMENT", "CREATE_INVOICE", "EDIT_DRAFT_INVOICE"),
				permissions(UserRole.SECRETARY));
	}

	@Test
	void securityOfficerHasNoUnspecifiedBusinessPrivilegesButStillRequiresMfa() {
		assertTrue(permissions(UserRole.SECURITY_OFFICER).isEmpty());
		assertTrue(RoleMfaPolicy.requiresAny(Set.of(UserRole.SECURITY_OFFICER)));
		RoleHierarchyPolicy hierarchy = new RoleHierarchyPolicy(new HttpSecurityConfig().roleHierarchy());
		assertFalse(hierarchy.canAssignRoles(Set.of(UserRole.SECURITY_OFFICER), Set.of(UserRole.EMPLOYEE)));
		assertFalse(hierarchy.canAssignRoles(Set.of(UserRole.ADMIN), Set.of(UserRole.SECURITY_OFFICER)));
	}

	@Test
	void missingRolesAndPermissionsCannotAuthorizeAnOperation() {
		assertFalse(RolePermissionPolicy.allows(null, Permission.MANAGE_USERS));
		assertFalse(RolePermissionPolicy.allows(UserRole.ADMIN, null));
	}

	private Set<String> permissions(UserRole role) {
		return Arrays.stream(Permission.values()).filter(permission -> RolePermissionPolicy.allows(role, permission))
				.map(Enum::name).collect(Collectors.toSet());
	}
}
