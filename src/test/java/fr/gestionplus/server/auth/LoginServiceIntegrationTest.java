package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.UserAccountEntity;
import fr.gestionplus.server.persistence.UserAccountJpaRepository;
import fr.gestionplus.server.persistence.UserCredentialEntity;
import fr.gestionplus.server.persistence.UserCredentialJpaRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
public class LoginServiceIntegrationTest {

	@Autowired
	private LoginService loginService;

	@Autowired
	private CredentialService credentialService;

	@Autowired
	private UserAccountJpaRepository accountRepository;

	@Autowired
	private EmployeeJpaRepository employeeRepository;

	@Autowired
	private UserCredentialJpaRepository credentialRepository;

	@MockitoBean(enforceOverride = true)
	private CompromisedPasswordChecker checker;

	@PersistenceContext
	private EntityManager entityManager;

	private UserAccount createTestAccount(boolean active) {

		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid", "0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userId, "test_" + userId,
				active ? UserAccountStatus.ACTIVE : UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		return account;
	}

	private void saveTestCredential(UserAccount account, String rawPassword) {

		Mockito.when(checker.isCompromised(rawPassword)).thenReturn(false);

		UserCredential credential = credentialService.createCredential(account.getUserID(), rawPassword);
		credentialRepository.saveAndFlush(new UserCredentialEntity(credential));
	}

	@Test
	void authenticatesUserFromDatabase() {

		UserAccount account = createTestAccount(true);
		String rawPassword = "jfkel-test-password";
		saveTestCredential(account, rawPassword);

		entityManager.clear();
		Optional<UserAccount> authenticated = loginService.authenticate(account.getUserName(), rawPassword);

		assertTrue(authenticated.isPresent());
		assertEquals(account.getUserID(), authenticated.get().getUserID());
		assertEquals(account.getEmployeeID(), authenticated.get().getEmployeeID());
		assertNotEquals(authenticated.get().getUserID(), authenticated.get().getEmployeeID());
		assertEquals(account.getUserName(), authenticated.get().getUserName());
	}

	@Test
	void badPassword() {
		UserAccount account = createTestAccount(true);
		String rawPassword = "jfkel-test-password";
		saveTestCredential(account, rawPassword);

		entityManager.clear();
		Optional<UserAccount> authenticated = loginService.authenticate(account.getUserName(), " jfkel-test-password");

		assertTrue(authenticated.isEmpty());
	}

	@Test
	void inactivAccount() {
		UserAccount account = createTestAccount(false);
		String rawPassword = "jfkel-test-password";
		saveTestCredential(account, rawPassword);

		entityManager.clear();
		Optional<UserAccount> authenticated = loginService.authenticate(account.getUserName(), "jfkel-test-password");

		assertFalse(authenticated.isPresent());
	}

	@Test
	void nullUser() {
		Optional<UserAccount> authenticated = loginService.authenticate(null, "jfkel-test-password");

		assertTrue(authenticated.isEmpty());
	}

	@Test
	void notCredentialAccount() {
		UserAccount account = createTestAccount(true);
		String rawPassword = "jfkel-test-password";
		
		entityManager.clear();
		Optional<UserAccount> authenticated = loginService.authenticate(account.getUserName(), rawPassword);

		assertFalse(authenticated.isPresent());
	}

}
