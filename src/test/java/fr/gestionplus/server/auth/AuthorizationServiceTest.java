package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.employee.Employee;

class AuthorizationServiceTest {

	private final Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35,
			"test@example.invalid", "0600000000", null);

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
		
		UserAccount account = new UserAccount(UUID.randomUUID(), "test", UserAccountStatus.ACTIVE, roles, employee.getEmployeeID());
		roles.add(UserRole.ADMIN);

		assertFalse(account.getRoles().contains(UserRole.ADMIN));
		assertFalse(AuthorizationService.can(account, Permission.MANAGE_USERS));
	}
	
	
}