package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

import fr.gestionplus.server.audit.AuditReason;
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
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());

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
		assertEquals(AuditReason.UNKNOWN_ACCOUNT, loginService.evaluate("utilisateurInconnu", "ujkel").reason());
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
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());

		assertFalse(loginService.authenticate(name, "jekel-test-password").isPresent());
		assertEquals(AuditReason.DISABLED_ACCOUNT, loginService.evaluate(name, "jekel-test-password").reason());
	}

	@Test
	void performsOneArgon2CheckForUnknownDisabledAndActiveAccounts() {
		PasswordEncoder measured = spy(encoder);
		CredentialService measuredCredentials = new CredentialService(measured, new PasswordPolicy(password -> false));
		LoginService measuredLogin = new LoginService(userAccountLookup, userCredentialLookup, measuredCredentials);

		assertEquals(AuditReason.UNKNOWN_ACCOUNT, measuredLogin.evaluate("unknown", "unknown-password").reason());
		assertEquals(AuditReason.INVALID_CREDENTIALS, measuredLogin.evaluate(name, "wrong-password").reason());
		userAccount = new UserAccount(userId, name, UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE),
				employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(), employee.getEmail(),
				employee.getPhoneNumber());
		assertEquals(AuditReason.DISABLED_ACCOUNT, measuredLogin.evaluate(name, "disabled-password").reason());

		verify(measured).matches(eq("unknown-password"), argThat(hash -> hash.startsWith("$argon2id$")));
		verify(measured).matches(eq("disabled-password"), argThat(hash -> hash.startsWith("$argon2id$")));
		verify(measured).matches("wrong-password", credential.getPasswordHash());
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
