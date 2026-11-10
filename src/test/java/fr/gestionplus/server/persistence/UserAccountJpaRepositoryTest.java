package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class UserAccountJpaRepositoryTest {

	@Autowired
	private UserAccountJpaRepository repository;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private AccountStore accountStore;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void savesAndFindsAccount() {

		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());
		UserAccountEntity entity = new UserAccountEntity(account);

		repository.saveAndFlush(entity);
		entityManager.clear();

		Optional<UserAccountEntity> accountEntity = repository.findByUserName(account.getUserName());

		assertTrue(accountEntity.isPresent());

		UserAccount restored = accountEntity.get().toDomain();

		assertEquals(account.getUserID(), restored.getUserID());
		assertEquals(employee.getEmployeeID(), restored.getEmployeeID());
		assertNotEquals(restored.getUserID(), restored.getEmployeeID());
		assertEquals(account.getUserName(), restored.getUserName());
		assertEquals(account.getRoles(), restored.getRoles());
		assertEquals(account.getLastName(), restored.getLastName());
		assertEquals(account.getFirstName(), restored.getFirstName());
		assertEquals(account.getEmail(), restored.getEmail());
		assertEquals(account.getPhoneNumber(), restored.getPhoneNumber());
		assertEquals(account.getStatu(), restored.getStatu());
	}

	@Test
	void persistsPendingAccountWithoutEmployeeOrGlobalRoles() {
		UUID userID = UUID.randomUUID();

		UserAccount pending = UserAccount.pending(userID, "Exemple", "Test", "test@example.invalid", "0600000000");
		accountStore.createAccount(pending);
		entityManager.flush();
		entityManager.clear();

		UserAccount restored = accountStore.findById(userID).orElseThrow();
		assertEquals(userID, restored.getUserID());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, restored.getStatu());
		assertEquals(pending.getLastName(), restored.getLastName());
		assertEquals(pending.getFirstName(), restored.getFirstName());
		assertEquals(pending.getEmail(), restored.getEmail());
		assertEquals(pending.getPhoneNumber(), restored.getPhoneNumber());
		assertNull(restored.getEmployeeID());
		assertNull(restored.getUserName());
		assertTrue(restored.getRoles().isEmpty());
	}

	@Test
	void rejectsNewAccountWithoutEmployee() {
		JpaAccountStore accountStore = new JpaAccountStore(repository, employeeRepository);
		UUID userId = UUID.randomUUID();
		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), null, "Exemple", "Test", "test@example.invalid", "0600000000");

		assertThrows(IllegalArgumentException.class, () -> accountStore.createAccount(account));
		assertFalse(repository.existsById(userId));
	}

	@Test
	void rejectsUnknownEmployeeAtCreation() {
		JpaAccountStore accountStore = new JpaAccountStore(repository, employeeRepository);
		UUID userId = UUID.randomUUID();
		UUID employeeID = UUID.randomUUID();
		assertFalse(employeeRepository.existsById(employeeID));

		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employeeID, "Exemple", "Test", "test@example.invalid", "0600000000");

		assertThrows(IllegalArgumentException.class, () -> accountStore.createAccount(account));
		assertFalse(repository.existsById(userId));
	}

	@Test
	void rejectsNewJpaAccountWithoutEmployee() {
		UUID userId = UUID.randomUUID();
		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), null, "Exemple", "Test", "test@example.invalid", "0600000000");

		assertThrows(IllegalStateException.class, () -> entityManager.persist(new UserAccountEntity(account)));
	}

	@Test
	void rejectsUnknownEmployeeInDatabase() {
		UUID userId = UUID.randomUUID();
		UUID employeeID = UUID.randomUUID();
		assertFalse(employeeRepository.existsById(employeeID));

		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employeeID, "Exemple", "Test", "test@example.invalid", "0600000000");

		assertThrows(DataIntegrityViolationException.class,
				() -> repository.saveAndFlush(new UserAccountEntity(account)));
	}
}