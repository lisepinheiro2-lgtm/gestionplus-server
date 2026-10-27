package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import fr.gestionplus.server.employee.Employee;

public class LoginServiceTest {

	private PasswordEncoder encoder;
	private CredentialService service;
	private UserAccountLookup userAccountLookup;
	private UserCredentialLookup userCredentialLookup;
	private LoginService loginService;
	private UUID userId;
	private Employee employee;
	private UserCredential credential;
	private String name;
	private UserAccount userAccount;

	@BeforeEach
	void setUp() {
		encoder = new PasswordSecurityConfig().passwordEncoder();
		service = new CredentialService(encoder, new PasswordPolicy(rawPassword -> false));
		userId = UUID.randomUUID();
		employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid", "0600000000", null);
		credential = service.createCredential(userId, "jekel-test-password");
		name = "userName";
		userAccount = new UserAccount(userId, name, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		userAccountLookup = name -> {
			if (name.equals(userAccount.getUserName())) {
				return Optional.of(userAccount);
			}
			return Optional.empty();
		};

		userCredentialLookup = id -> {
			if (id.equals(userId)) {
				return Optional.of(credential);
			}
			return Optional.empty();
		};

		loginService = new LoginService(userAccountLookup, userCredentialLookup, service);
	}

	@Test
	void rejectsUnknownUser() {
	    assertTrue(loginService.authenticate("utilisateurInconnu", "ujkel").isEmpty());
	}

	@Test
	void authenticatesActiveUser() {
		Optional<UserAccount> authenticated = loginService.authenticate(name, "jekel-test-password");

		assertTrue(authenticated.isPresent());
		assertEquals(userId, authenticated.get().getUserID());
		assertEquals(employee.getEmployeeID(), authenticated.get().getEmployeeID());
		assertFalse(loginService.authenticate("otherUser", "jekel-test-password").isPresent());
	}

	@Test
	void rejectsInactiveUser() {
		userAccount = new UserAccount(userId, name, UserAccountStatus.DISABLED,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		assertFalse(loginService.authenticate(name, "jekel-test-password").isPresent());
	}

	@Test
	void rejectsIncorrectPassword() {
		assertFalse(loginService.authenticate(name, "kkllmm").isPresent());
	}

	@Test
	void rejectsMissingCredential() {
		userCredentialLookup = userId -> Optional.empty();
		loginService = new LoginService(userAccountLookup, userCredentialLookup, service);

		assertTrue(loginService.authenticate(name, "jekel-test-password").isEmpty());
	}

	@Test
	void rejectsMismatchedCredential() {
		credential = service.createCredential(UUID.randomUUID(), "jekel-test-password");
		
		assertTrue(loginService.authenticate(name, "jekel-test-password").isEmpty());
	}
}
