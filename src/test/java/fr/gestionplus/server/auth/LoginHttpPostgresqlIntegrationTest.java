package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.UserAccountEntity;
import fr.gestionplus.server.persistence.UserAccountJpaRepository;
import fr.gestionplus.server.persistence.UserCredentialEntity;
import fr.gestionplus.server.persistence.UserCredentialJpaRepository;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_auth_http_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true" })
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

	@MockitoBean(enforceOverride = true)
	private CompromisedPasswordChecker checker;

	private UserAccount createTestAccount(boolean active) {

		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Employee", 35, "test@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userId, "test_" + userId,
				active ? UserAccountStatus.ACTIVE : UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE),
				employee.getEmployeeID());

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
	}

}
