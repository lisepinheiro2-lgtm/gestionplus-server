
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class UserCredentialJpaRepositoryTest {

	@Autowired
	private UserAccountJpaRepository accountRepository;

	@Autowired
	private EmployeeJpaRepository employeeRepository;

	@Autowired
	private UserCredentialJpaRepository credentialRepository;

	@Autowired
	private PasswordEncoder encoder;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void savesAndFindsCredential() {

		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid", "0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		String password = "MotDePasseTest123!";
		String passwordHash = encoder.encode(password);
		UserCredential credential = new UserCredential(userId, passwordHash);

		credentialRepository.saveAndFlush(new UserCredentialEntity(credential));

		entityManager.clear();

		Optional<UserCredentialEntity> found = credentialRepository.findById(userId);

		assertTrue(found.isPresent());

		UserCredential restored = found.get().toDomain();

		assertEquals(userId, restored.getUserId());
		assertEquals(employee.getEmployeeID(), accountRepository.findById(userId).orElseThrow().toDomain().getEmployeeID());
		assertEquals(passwordHash, restored.getPasswordHash());
		assertTrue(encoder.matches(password, restored.getPasswordHash()));
	}
}
