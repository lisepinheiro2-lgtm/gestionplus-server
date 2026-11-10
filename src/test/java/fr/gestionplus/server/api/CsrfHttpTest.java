
package fr.gestionplus.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import fr.gestionplus.server.auth.GestionAuthenticationProvider;
import fr.gestionplus.server.auth.HttpSecurityConfig;
import fr.gestionplus.server.auth.LoginService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

@WebMvcTest({ CsrfController.class, AuthSessionController.class })
@Import({ HttpSecurityConfig.class, GestionAuthenticationProvider.class })
class CsrfHttpTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private LoginService loginService;

	private final String userName = "alice";
	private final String password = "test-password";

	@Test
	void returnsCsrfToken() throws Exception {

		MvcResult result = requestCsrf(null);

		String token = getToken(result);

		assertNotNull(token);
		assertFalse(token.isBlank());
		assertNotNull(result.getRequest().getSession(false));
	}

	@Test
	void rejectsLoginWithoutCsrfToken() throws Exception {

		mockMvc.perform(post("/login").param("username", userName).param("password", password))
				.andExpect(status().isForbidden());
	}

	@Test
	void acceptsValidLoginAndStoresSession() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.authenticate(userName, password)).thenReturn(Optional.of(account));

		MvcResult csrfResult = requestCsrf(null);
		String token = getToken(csrfResult);

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		MvcResult loginResult = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();

		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertNotNull(authenticatedSession);

		SecurityContext context = (SecurityContext) authenticatedSession
				.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

		assertNotNull(context);
		assertTrue(context.getAuthentication().isAuthenticated());
		assertEquals(account, context.getAuthentication().getPrincipal());
	}

	@Test
	void rejectsIncorrectPassword() throws Exception {

		when(loginService.authenticate(userName, "wrong-password")).thenReturn(Optional.empty());

		MvcResult csrfResult = requestCsrf(null);
		String token = getToken(csrfResult);

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token).param("username", userName)
				.param("password", "wrong-password")).andExpect(status().isUnauthorized());
	}

	@Test
	void requiresFreshCsrfTokenAfterLogin() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.authenticate(userName, password)).thenReturn(Optional.of(account));

		MvcResult firstCsrf = requestCsrf(null);
		String oldToken = getToken(firstCsrf);

		MockHttpSession session = (MockHttpSession) firstCsrf.getRequest().getSession(false);

		MvcResult loginResult = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", oldToken)
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();

		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);

		mockMvc.perform(post("/login").session(authenticatedSession).header("X-CSRF-TOKEN", oldToken)
				.param("username", userName).param("password", password)).andExpect(status().isForbidden());

		MvcResult newCsrf = requestCsrf(authenticatedSession);
		String newToken = getToken(newCsrf);

		mockMvc.perform(post("/login").session(authenticatedSession).header("X-CSRF-TOKEN", newToken)
				.param("username", userName).param("password", password)).andExpect(status().isOk());
	}

	private MvcResult requestCsrf(MockHttpSession session) throws Exception {

		if (session == null) {
			return mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk())
					.andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
		}

		return mockMvc.perform(get("/api/auth/csrf").session(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
	}

	private String getToken(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	private UserAccount createTestAccount() {
		return new UserAccount(UUID.randomUUID(), userName, UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE),
				UUID.randomUUID(), "Exemple", "Test", "test@example.invalid", "0600000000");
	}

	@Test
	void recognizesAuthenticatedUserOnNextRequest() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.authenticate(userName, password)).thenReturn(Optional.of(account));

		MvcResult csrfResult = requestCsrf(null);
		String token = getToken(csrfResult);

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		MvcResult loginResult = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();

		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertNotNull(authenticatedSession);

		mockMvc.perform(get("/api/auth/me").session(authenticatedSession)).andExpect(status().isOk())
				.andExpect(jsonPath("$.userName").value(userName));
	}

	@Test
	void refusesCurrentUserWithoutSession() throws Exception {
		mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutInvalidatesCurrentSession() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.authenticate(userName, password)).thenReturn(Optional.of(account));
		MvcResult csrfResult = requestCsrf(null);

		String token = getToken(csrfResult);
		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		MvcResult loginResult = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();

		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
		assertNotNull(authenticatedSession);

		mockMvc.perform(get("/api/auth/me").session(authenticatedSession)).andExpect(status().isOk());

		MvcResult newCsrf = requestCsrf(authenticatedSession);
		String newToken = getToken(newCsrf);

		mockMvc.perform(post("/logout").session(authenticatedSession).header("X-CSRF-TOKEN", newToken))
				.andExpect(status().isNoContent());
		assertTrue(authenticatedSession.isInvalid());
	}

	@Test
	void logoutWithoutCsrfKeepsSessionActive() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.authenticate(userName, password)).thenReturn(Optional.of(account));

		MvcResult csrfResult = requestCsrf(null);
		String token = getToken(csrfResult);

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		MvcResult loginResult = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token)
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();

		MockHttpSession authenticatedSession = (MockHttpSession) loginResult.getRequest().getSession(false);
	
		mockMvc.perform(post("/logout").session(authenticatedSession)).andExpect(status().isForbidden());
		assertFalse(authenticatedSession.isInvalid());

		mockMvc.perform(get("/api/auth/me").session(authenticatedSession)).andExpect(status().isOk())
				.andExpect(jsonPath("$.userName").value(userName));
	}

}
