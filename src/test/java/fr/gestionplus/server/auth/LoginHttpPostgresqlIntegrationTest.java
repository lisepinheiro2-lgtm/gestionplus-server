package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.auth.AccountMfaService.Verification;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.TenantOrganizationEntity;
import fr.gestionplus.server.persistence.TenantOrganizationJpaRepository;
import fr.gestionplus.server.persistence.UserAccountEntity;
import fr.gestionplus.server.persistence.UserAccountJpaRepository;
import fr.gestionplus.server.persistence.UserCredentialEntity;
import fr.gestionplus.server.persistence.UserCredentialJpaRepository;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_auth_http_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true", "gestionplus.mfa.key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
@AutoConfigureMockMvc
public class LoginHttpPostgresqlIntegrationTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;
	@Autowired
	private UserCredentialJpaRepository credentialRepository;
	@Autowired
	private CredentialService credentialService;
	@Autowired
	private SessionRegistry sessionRegistry;
	@Autowired
	private SessionRevocationService sessionRevocationService;

	@Autowired
	private AccountStore accountStore;
	@Autowired
	private AccountMfaStore mfaStore;
	@Autowired
	private AccountMfaService mfa;
	@Autowired
	private MfaSecretCipher cipher;
	@Autowired
	private TenantOrganizationJpaRepository tenantRepository;
	@Autowired
	private PasswordChangeService passwordChange;
	@Autowired
	private JdbcTemplate jdbc;

	@MockitoBean(enforceOverride = true)
	private CompromisedPasswordChecker checker;

	@MockitoBean
	private MfaEnrollmentVerifier enrollmentVerifier;

	private UserAccount createTestAccount(boolean active) {

		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userId, "test_" + userId,
				active ? UserAccountStatus.ACTIVE : UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE),
				employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		return account;
	}

	private void saveTestCredential(UserAccount account, String rawPassword) {

		Mockito.when(checker.isCompromised(rawPassword)).thenReturn(false);

		UserCredential credential = credentialService.createCredential(account.getUserID(), rawPassword);
		credentialRepository.saveAndFlush(new UserCredentialEntity(credential));
	}

	@Test
	void authenticatesStoredAccountThroughHttp() throws Exception {

		UserAccount account = createTestAccount(true);
		String rawPassword = "jfkel-test-password";

		saveTestCredential(account, rawPassword);

		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

		String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");
		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
		assertNotNull(session);

		MvcResult loginResult = mockMvc
				.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
						.param("username", account.getUserName()).param("password", rawPassword))
				.andExpect(status().isOk()).andReturn();

		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertNotNull(authenticatedSession);

		mockMvc.perform(get("/api/auth/me").session(authenticatedSession)).andExpect(status().isOk())
				.andExpect(jsonPath("$.userName").value(account.getUserName()));
	}

	@Test
	void rejectsWrongPasswordThroughHttp() throws Exception {

		UserAccount account = createTestAccount(true);
		String rawPassword = "jfkel-test-password";

		saveTestCredential(account, rawPassword);

		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

		String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
				.param("username", account.getUserName()).param("password", "wrong-password"))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void rejectsDisabledAccountThroughHttp() throws Exception {

		UserAccount account = createTestAccount(false);
		String rawPassword = "jfkel-test-password";

		saveTestCredential(account, rawPassword);

		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();

		String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
				.param("username", account.getUserName()).param("password", rawPassword))
				.andExpect(status().isUnauthorized());
	}

	private MockHttpSession openAuthenticatedSession(UserAccount account, String rawPassword) throws Exception {

		MvcResult csrfResult = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		String token = JsonPath.read(csrfResult.getResponse().getContentAsString(), "$.token");

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
		assertNotNull(session);

		MvcResult loginResult = mockMvc
				.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
						.param("username", account.getUserName()).param("password", rawPassword))
				.andExpect(status().isOk()).andReturn();

		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertNotNull(authenticatedSession);

		return authenticatedSession;
	}

	@Test
	void requiresTotpForTheDesignatedSuperAdminAndRejectsReplay() throws Exception {
		UserAccount account = createTestAccount(true);
		String password = "jfkel-test-password";
		saveTestCredential(account, password);

		UUID tenantID = UUID.randomUUID();
		tenantRepository.saveAndFlush(new TenantOrganizationEntity(
				new TenantOrganization(tenantID, "Entreprise " + tenantID, account.getUserID())));

		assertEquals(Verification.DENIED, mfa.verifyForLogin(account, null));
		byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
		mfaStore.saveFactor(account.getUserID(), cipher.encrypt(account.getUserID(), null, secret), -1, List.of());

		MockHttpServletRequestBuilder request = loginRequest(account, password);
		String code = currentCode(secret);
		MvcResult login = mockMvc.perform(request.param("totp", code)).andExpect(status().isOk()).andReturn();
		MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk());

		mockMvc.perform(loginRequest(account, password).param("totp", code)).andExpect(status().isUnauthorized());
	}

	@Test
	void recoveryConsumesAllOldCodesRevokesSessionsAndRequiresNewEnrollment() throws Exception {
		UserAccount account = createTestAccount(true);
		UUID userID = account.getUserID();
		String password = "jfkel-test-password";
		saveTestCredential(account, password);
		MockHttpSession priorSession = openAuthenticatedSession(account, password);

		assertThrows(AccessDeniedException.class, () -> mfa.beginEnrollment(userID, "unverified"));
		Mockito.when(enrollmentVerifier.consumeVerifiedIdentity(userID, account.getSessionVersion(), "verified"))
				.thenReturn(true);
		var enrollment = mfa.beginEnrollment(userID, "verified");
		var codes = mfa.completeEnrollment(userID, enrollment.enrollmentID(), enrollmentCode(userID)).codes();

		List<String> hashes = jdbc.queryForList(
				"SELECT code_hash FROM account_mfa_recovery_codes WHERE user_id = ?", String.class, userID);
		assertEquals(10, hashes.size());
		assertTrue(hashes.stream().allMatch(hash -> hash.matches("[0-9a-f]{64}")));
		assertFalse(hashes.stream().anyMatch(codes::contains));
		mockMvc.perform(get("/api/auth/me").session(priorSession)).andExpect(status().isUnauthorized());

		UserAccount configured = accountStore.findById(userID).orElseThrow();
		String registeredSession = "configured-" + userID;
		sessionRegistry.registerNewSession(registeredSession, configured);
		var replacement = mfa.recover(account.getUserName(), password, codes.getFirst());

		assertTrue(sessionRegistry.getSessionInformation(registeredSession).isExpired());
		assertThrows(AccessDeniedException.class, () -> mfa.recover(account.getUserName(), password, codes.getFirst()));
		assertThrows(AccessDeniedException.class, () -> mfa.recover(account.getUserName(), password, codes.get(1)));
		UserAccount recovering = accountStore.findById(userID).orElseThrow();
		assertEquals(configured.getSessionVersion() + 1, recovering.getSessionVersion());
		assertEquals(Verification.DENIED, mfa.verifyForLogin(recovering, null));
		assertNotEquals(enrollment.setupKey(), replacement.setupKey());

		var freshCodes = mfa.completeEnrollment(userID, replacement.enrollmentID(), enrollmentCode(userID)).codes();
		assertEquals(10, freshCodes.size());
		assertFalse(freshCodes.stream().anyMatch(codes::contains));
	}

	@Test
	void passwordChangeVerifiesTheCurrentPasswordAndExpiresEveryExistingSession() throws Exception {
		UserAccount account = createTestAccount(true);
		String oldPassword = "jfkel-test-password";
		String newPassword = "replacement-test-password";
		saveTestCredential(account, oldPassword);
		MockHttpSession first = openAuthenticatedSession(account, oldPassword);
		MockHttpSession second = openAuthenticatedSession(account, oldPassword);

		try {
			SecurityContextHolder.getContext().setAuthentication(
					UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
			assertThrows(AccessDeniedException.class,
					() -> passwordChange.changePassword("incorrect", newPassword, null));
			assertEquals(2, sessionRegistry.getAllSessions(account, false).size());

			passwordChange.changePassword(oldPassword, newPassword, null);

			assertThrows(AccessDeniedException.class,
					() -> passwordChange.changePassword(newPassword, oldPassword, null));
		} finally {
			SecurityContextHolder.clearContext();
		}

		assertEquals(0, sessionRegistry.getAllSessions(account, false).size());
		mockMvc.perform(get("/api/auth/me").session(first)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/auth/me").session(second)).andExpect(status().isUnauthorized());

		UserCredential stored = credentialRepository.findById(account.getUserID()).orElseThrow().toDomain();
		assertFalse(credentialService.verifyPassword(stored, oldPassword));
		assertTrue(credentialService.verifyPassword(stored, newPassword));
		openAuthenticatedSession(account, newPassword);
	}

	@Test
	void passwordChangeRequiresAFreshTotpForAnEnrolledAccount() {
		UserAccount account = createTestAccount(true);
		String password = "jfkel-test-password";
		String replacement = "replacement-test-password";
		saveTestCredential(account, password);
		byte[] secret = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
		mfaStore.saveFactor(account.getUserID(), cipher.encrypt(account.getUserID(), null, secret), -1, List.of());

		try {
			SecurityContextHolder.getContext().setAuthentication(
					UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
			assertThrows(AccessDeniedException.class, () -> passwordChange.changePassword(password, replacement, null));
			passwordChange.changePassword(password, replacement, currentCode(secret));
		} finally {
			SecurityContextHolder.clearContext();
		}

		UserCredential stored = credentialRepository.findById(account.getUserID()).orElseThrow().toDomain();
		assertTrue(credentialService.verifyPassword(stored, replacement));
	}

	@Test
	void passwordChangeCannotReactivateADisabledAccount() {
		UserAccount account = createTestAccount(false);
		String password = "jfkel-test-password";
		saveTestCredential(account, password);

		try {
			SecurityContextHolder.getContext().setAuthentication(
					UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
			assertThrows(AccessDeniedException.class,
					() -> passwordChange.changePassword(password, "replacement-test-password", null));
		} finally {
			SecurityContextHolder.clearContext();
		}

		assertEquals(UserAccountStatus.DISABLED, accountStore.findById(account.getUserID()).orElseThrow().getStatu());
		UserCredential stored = credentialRepository.findById(account.getUserID()).orElseThrow().toDomain();
		assertTrue(credentialService.verifyPassword(stored, password));
	}

	private String enrollmentCode(UUID userID) {
		var pending = mfaStore.findEnrollment(userID).orElseThrow();
		return currentCode(cipher.decrypt(userID, pending.enrollmentID(), pending.encryptedSecret()));
	}

	private String currentCode(byte[] secret) {
		return TotpCodes.atStep(secret, Instant.now().getEpochSecond() / 30);
	}

	private MockHttpServletRequestBuilder loginRequest(UserAccount account, String password) throws Exception {
		MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
		String token = JsonPath.read(csrf.getResponse().getContentAsString(), "$.token");
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		return post("/login").session(session).header("X-CSRF-TOKEN", token)
				.param("username", account.getUserName()).param("password", password);
	}

	@Test
	void allowsTwoSessionsForSameAccount() throws Exception {

		UserAccount account = createTestAccount(true);
		String rawPassword = "jfkel-test-password";

		saveTestCredential(account, rawPassword);

		MockHttpSession firstSession = openAuthenticatedSession(account, rawPassword);
		MockHttpSession secondSession = openAuthenticatedSession(account, rawPassword);
		assertNotEquals(firstSession.getId(), secondSession.getId());

		mockMvc.perform(get("/api/auth/me").session(firstSession)).andExpect(status().isOk())
				.andExpect(jsonPath("$.userName").value(account.getUserName()));

		mockMvc.perform(get("/api/auth/me").session(secondSession)).andExpect(status().isOk())
				.andExpect(jsonPath("$.userName").value(account.getUserName()));

		assertEquals(2, sessionRegistry.getAllSessions(account, false).size());
		assertNotNull(sessionRegistry.getSessionInformation(firstSession.getId()));
		assertNotNull(sessionRegistry.getSessionInformation(secondSession.getId()));
	}

	@Test
	void rejectsAllExpiredSessionsForSameAccount() throws Exception {

		UserAccount account = createTestAccount(true);
		String rawPassword = "jfkel-test-password";

		saveTestCredential(account, rawPassword);

		MockHttpSession firstSession = openAuthenticatedSession(account, rawPassword);
		MockHttpSession secondSession = openAuthenticatedSession(account, rawPassword);
		assertEquals(2, sessionRegistry.getAllSessions(account, false).size());

		sessionRevocationService.revokeAllSessions(account);
		assertEquals(0, sessionRegistry.getAllSessions(account, false).size());

		mockMvc.perform(get("/api/auth/me").session(firstSession)).andExpect(status().isUnauthorized());
		mockMvc.perform(get("/api/auth/me").session(secondSession)).andExpect(status().isUnauthorized());
	}

}
