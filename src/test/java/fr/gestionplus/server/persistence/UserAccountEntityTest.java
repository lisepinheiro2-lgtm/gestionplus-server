package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;

public class UserAccountEntityTest {

	@Test
	void preservesAccountDuringConversion() {
		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
				"0600000000", null);
		String name = "userName";
		UserAccount userAccount = new UserAccount(userId, name, UserAccountStatus.ACTIVE,
				Set.of(UserRole.SECRETARY, UserRole.ACCOUNTANT), employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());
		UserAccountEntity accountEntity = new UserAccountEntity(userAccount);
		UserAccount restored = accountEntity.toDomain();

		assertEquals(userAccount.getUserID(), restored.getUserID());
		assertEquals(employee.getEmployeeID(), restored.getEmployeeID());
		assertNotEquals(restored.getUserID(), restored.getEmployeeID());
		assertEquals(userAccount.getUserName(), restored.getUserName());
		assertEquals(userAccount.getStatu(), restored.getStatu());
		assertEquals(userAccount.getRoles(), restored.getRoles());
		assertEquals(userAccount.getLastName(), restored.getLastName());
		assertEquals(userAccount.getFirstName(), restored.getFirstName());
		assertEquals(userAccount.getEmail(), restored.getEmail());
		assertEquals(userAccount.getPhoneNumber(), restored.getPhoneNumber());
	}

	@Test
	void preservesRolelessAccountWhenDisabled() {
		UUID userID = UUID.randomUUID();
		UserAccount pending = UserAccount.pending(userID, "Exemple", "Test", "test@example.invalid", "0600000000");
		UserAccountEntity entity = new UserAccountEntity(pending);

		entity.disable();

		UserAccount restored = entity.toDomain();
		assertEquals(userID, restored.getUserID());
		assertEquals(UserAccountStatus.DISABLED, restored.getStatu());
		assertNull(restored.getUserName());
		assertNull(restored.getEmployeeID());
		assertTrue(restored.getRoles().isEmpty());
	}

	@Test
	void rejectsGlobalRoleWhenCreatingPendingAccount() {
		UserAccount account = new UserAccount(UUID.randomUUID(), null, UserAccountStatus.PENDING_ACTIVATION,
				Set.of(UserRole.EMPLOYEE), null, "Exemple", "Test", "test@example.invalid", "0600000000");

		assertThrows(IllegalStateException.class, new UserAccountEntity(account)::validateCreation);
	}

	@Test
	void preservesHistoricalAccountWithoutEmployeeDuringConversion() {
		UUID userId = UUID.randomUUID();
		UserAccount userAccount = new UserAccount(userId, "historicalUser", UserAccountStatus.ACTIVE,
				Set.of(UserRole.ADMIN), null, "Exemple", "Test", "test@example.invalid", "0600000000");

		UserAccount restored = new UserAccountEntity(userAccount).toDomain();

		assertEquals(userAccount.getUserID(), restored.getUserID());
		assertEquals(userAccount.getUserName(), restored.getUserName());
		assertEquals(userAccount.getStatu(), restored.getStatu());
		assertEquals(userAccount.getRoles(), restored.getRoles());
		assertNull(restored.getEmployeeID());
	}
}
