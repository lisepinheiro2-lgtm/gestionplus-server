
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_account_disable_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true" })
@Transactional
class UserAccountDisablePostgresqlIntegrationTest {

	@Autowired
	private UserAccountJpaRepository accountRepository;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private AccountStore accountStore;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void persistsDisabledAccountWithoutChangingIdentity() {

		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "test_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		accountRepository.saveAndFlush(new UserAccountEntity(account));
		entityManager.clear();

		UserAccountEntity entity = accountRepository.findById(userID).orElseThrow();
		assertEquals(UserAccountStatus.ACTIVE, entity.toDomain().getStatu());

		entity.disable();

		accountRepository.saveAndFlush(entity);
		entityManager.clear();

		UserAccount restored = accountRepository.findById(userID).orElseThrow().toDomain();

		assertEquals(UserAccountStatus.DISABLED, restored.getStatu());
		assertEquals(account.getUserID(), restored.getUserID());
		assertEquals(account.getEmployeeID(), restored.getEmployeeID());
		assertEquals(account.getUserName(), restored.getUserName());
		assertEquals(account.getRoles(), restored.getRoles());
	}

	@Test
	void disablesAccountThroughAccountStore() {

		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "test_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());
		accountRepository.saveAndFlush(new UserAccountEntity(account));
		entityManager.clear();

		accountStore.disableAccount(userID);

		entityManager.flush();
		entityManager.clear();

		UserAccount restored = accountRepository.findById(userID).orElseThrow().toDomain();
		assertEquals(UserAccountStatus.DISABLED, restored.getStatu());
		assertEquals(account.getUserID(), restored.getUserID());
		assertEquals(account.getEmployeeID(), restored.getEmployeeID());
		assertEquals(account.getRoles(), restored.getRoles());
	}

	@Test
	void findsAccountByIdThroughAccountStore() {

		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "test_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());
		accountRepository.saveAndFlush(new UserAccountEntity(account));
		entityManager.clear();

		UserAccount restored = accountStore.findById(userID).orElseThrow();

		assertEquals(account.getUserID(), restored.getUserID());
		assertEquals(account.getUserName(), restored.getUserName());
		assertEquals(account.getStatu(), restored.getStatu());
		assertEquals(account.getRoles(), restored.getRoles());
		assertEquals(account.getEmployeeID(), restored.getEmployeeID());
		assertTrue(accountStore.findById(UUID.randomUUID()).isEmpty());
	}

}
