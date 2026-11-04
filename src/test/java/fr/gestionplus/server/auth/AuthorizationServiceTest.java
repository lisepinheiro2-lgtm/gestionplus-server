package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.organization.StructureMembership;

class AuthorizationServiceTest {

	private final Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
			"0600000000", null);

	@Test
	void inactiveAdminCannotManageUsers() {
		UserAccount account = new UserAccount(UUID.randomUUID(), "adminTest", UserAccountStatus.DISABLED,
				Set.of(UserRole.ADMIN), employee.getEmployeeID());

		assertFalse(AuthorizationService.can(account, Permission.MANAGE_USERS));
	}

	@Test
	void adminCanManageUsers() {
		UserAccount account = new UserAccount(UUID.randomUUID(), "adminTest", UserAccountStatus.ACTIVE,
				Set.of(UserRole.ADMIN), employee.getEmployeeID());

		assertTrue(AuthorizationService.can(account, Permission.MANAGE_USERS));
	}

	@Test
	void employeeCannotManageUsers() {
		UserAccount account = new UserAccount(UUID.randomUUID(), "employeeTest", UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		assertFalse(AuthorizationService.can(account, Permission.MANAGE_USERS));
	}

	@Test
	void secretaryAccountantCanViewAccounting() {
		UserAccount account = new UserAccount(UUID.randomUUID(), "sophieTest", UserAccountStatus.ACTIVE,
				Set.of(UserRole.SECRETARY, UserRole.ACCOUNTANT), employee.getEmployeeID());

		assertTrue(AuthorizationService.can(account, Permission.VIEW_ACCOUNTING));
		assertFalse(AuthorizationService.can(account, Permission.MANAGE_USERS));
		assertFalse(AuthorizationService.can(account, Permission.EDIT_SENSITIVE_DOCUMENT));
	}

	@Test
	void cannotModifyUserRoles() {
		UserAccount account = new UserAccount(UUID.randomUUID(), "modifRoleTest", UserAccountStatus.ACTIVE,
				Set.of(UserRole.MANAGER), employee.getEmployeeID());

		assertThrows(UnsupportedOperationException.class, () -> account.getRoles().add(UserRole.ADMIN));
	}

	@Test
	void modifyingOriginalRolesDoesNotChangeAccount() {
		Set<UserRole> roles = new HashSet<>();
		roles.add(UserRole.EMPLOYEE);

		UserAccount account = new UserAccount(UUID.randomUUID(), "test", UserAccountStatus.ACTIVE, roles,
				employee.getEmployeeID());
		roles.add(UserRole.ADMIN);

		assertFalse(account.getRoles().contains(UserRole.ADMIN));
		assertFalse(AuthorizationService.can(account, Permission.MANAGE_USERS));
	}

	@Test
	void adminCanManageUsersInOwnStructure() {

		UUID structureID = UUID.randomUUID();

		UserAccount account = new UserAccount(UUID.randomUUID(), "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		StructureMembership membership = new StructureMembership(account.getUserID(), structureID,
				Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));

		assertTrue(AuthorizationService.canInStructure(account, membership, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void globalAdminCannotManageUsersWithEmployeeMembership() {

		UUID structureA = UUID.randomUUID();
		UUID structureB = UUID.randomUUID();

		UserAccount account = new UserAccount(UUID.randomUUID(), "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.ADMIN), employee.getEmployeeID());

		StructureMembership membershipA = new StructureMembership(account.getUserID(), structureA,
				Set.of(UserRole.ADMIN));

		StructureMembership membershipB = new StructureMembership(account.getUserID(), structureB,
				Set.of(UserRole.EMPLOYEE));

		assertTrue(AuthorizationService.canInStructure(account, membershipA, structureA, Permission.MANAGE_USERS));

		assertFalse(AuthorizationService.canInStructure(account, membershipB, structureB, Permission.MANAGE_USERS));
	}

	@Test
	void rejectsMembershipBelongingToAnotherUser() {

		UUID structureID = UUID.randomUUID();

		UserAccount account = new UserAccount(UUID.randomUUID(), "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		StructureMembership membership = new StructureMembership(UUID.randomUUID(), structureID,
				Set.of(UserRole.ADMIN));

		assertFalse(AuthorizationService.canInStructure(account, membership, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void rejectsMembershipFromAnotherStructure() {

		UUID structureA = UUID.randomUUID();
		UUID structureB = UUID.randomUUID();

		UserAccount account = new UserAccount(UUID.randomUUID(), "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		StructureMembership membership = new StructureMembership(account.getUserID(), structureA,
				Set.of(UserRole.ADMIN));

		assertFalse(AuthorizationService.canInStructure(account, membership, structureB, Permission.MANAGE_USERS));
	}

	@Test
	void rejectsInactiveAccountsInStructure() {

		UUID userID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();

		StructureMembership membership = new StructureMembership(userID, structureID, Set.of(UserRole.ADMIN));

		UserAccount disabled = new UserAccount(userID, "alice", UserAccountStatus.DISABLED, Set.of(UserRole.ADMIN),
				employee.getEmployeeID());

		UserAccount pending = new UserAccount(userID, "alice", UserAccountStatus.PENDING_ACTIVATION,
				Set.of(UserRole.ADMIN), employee.getEmployeeID());

		assertFalse(AuthorizationService.canInStructure(disabled, membership, structureID, Permission.MANAGE_USERS));

		assertFalse(AuthorizationService.canInStructure(pending, membership, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void rejectsNullParametersInStructure() {

		UUID structureID = UUID.randomUUID();
		UserAccount account = new UserAccount(UUID.randomUUID(), "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.ADMIN), employee.getEmployeeID());
		StructureMembership membership = new StructureMembership(account.getUserID(), structureID,
				Set.of(UserRole.ADMIN));

		assertFalse(AuthorizationService.canInStructure(null, membership, structureID, Permission.MANAGE_USERS));
		assertFalse(AuthorizationService.canInStructure(account, null, structureID, Permission.MANAGE_USERS));
		assertFalse(AuthorizationService.canInStructure(account, membership, null, Permission.MANAGE_USERS));
		assertFalse(AuthorizationService.canInStructure(account, membership, structureID, null));
	}

}