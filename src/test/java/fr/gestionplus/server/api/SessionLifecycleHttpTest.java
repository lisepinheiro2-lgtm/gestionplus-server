package fr.gestionplus.server.api;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AccountMfaService.Verification;
import fr.gestionplus.server.auth.GestionAuthenticationProvider;
import fr.gestionplus.server.auth.HttpSecurityConfig;
import fr.gestionplus.server.auth.LoginAttemptLimiter;
import fr.gestionplus.server.auth.LoginDelay;
import fr.gestionplus.server.auth.LoginService;
import fr.gestionplus.server.auth.LoginService.LoginAttempt;
import fr.gestionplus.server.auth.SessionLifetime;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

@WebMvcTest({ CsrfController.class, AuthSessionController.class })
@Import({ HttpSecurityConfig.class, GestionAuthenticationProvider.class, LoginAttemptLimiter.class,
		SessionLifecycleHttpTest.SessionClockConfiguration.class })
class SessionLifecycleHttpTest {

	private static final Instant NOW = Instant.parse("2026-11-19T10:00:00Z");
	private static final String PASSWORD = "session-http-test-password";

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private SessionRegistry sessionRegistry;

	@MockitoBean
	private Clock clock;
	@MockitoBean
	private LoginService loginService;
	@MockitoBean
	private AccountStore accountStore;
	@MockitoBean
	private AuditService auditService;
	@MockitoBean
	private AccountMfaService mfa;
	@MockitoBean
	private LoginDelay loginDelay;

	@BeforeEach
	void prepareClockAndMfa() {
		when(clock.instant()).thenReturn(NOW);
		when(mfa.verifyForLogin(any(UserAccount.class), nullable(String.class))).thenReturn(Verification.NOT_REQUIRED);
	}

	@AfterEach
	void clearRegisteredSessionsAndSecurityContext() {
		for (Object principal : sessionRegistry.getAllPrincipals()) {
			for (SessionInformation information : sessionRegistry.getAllSessions(principal, true)) {
				sessionRegistry.removeSessionInformation(information.getSessionId());
			}
		}
		SecurityContextHolder.clearContext();
	}

	@Test
	void loginCurrentUserAndLogoutInvalidateAccessOnTheFollowingRequest() throws Exception {
		UserAccount account = createTestAccount();
		MockHttpSession session = openAuthenticatedSession(account);

		assertCurrentUser(session, account);
		logoutAndAssertProtectedReadsDenied(session);

		verify(auditService).recordForActor(eq(AuditAction.AUTH_LOGOUT), eq(AuditResult.SUCCESS),
				eq(account.getUserID()), anyString(),
				eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID()))), eq(AuditMetadata.empty()));
	}

	@Test
	void expirationRejectsTheAuthenticatedSessionAndAllowsAnonymousLogout() throws Exception {
		UserAccount account = createTestAccount();
		MockHttpSession session = openAuthenticatedSession(account);
		assertCurrentUser(session, account);
		MvcResult expiration = mockMvc.perform(get("/api/auth/session").session(session))
				.andExpect(status().isOk()).andExpect(jsonPath("$.expiresAt").isNotEmpty()).andReturn();
		String expiresAt = JsonPath.read(expiration.getResponse().getContentAsString(), "$.expiresAt");

		when(clock.instant()).thenReturn(Instant.parse(expiresAt));

		MvcResult refused = mockMvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}")).andReturn();

		assertTrue(session.isInvalid());
		assertNull(refused.getRequest().getSession(false));
		verify(auditService).recordForActor(eq(AuditAction.AUTH_ACCESS_ATTEMPT), eq(AuditResult.DENIED),
				eq(account.getUserID()), anyString(),
				eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID()))),
				eq(AuditMetadata.because(AuditReason.SESSION_EXPIRED)));
		logoutAndAssertProtectedReadsDenied(session);
	}

	@Test
	void invalidationRejectsTheFormerSessionAndAllowsAnonymousLogout() throws Exception {
		UserAccount account = createTestAccount();
		MockHttpSession session = openAuthenticatedSession(account);
		assertCurrentUser(session, account);

		session.invalidate();

		MvcResult refused = mockMvc.perform(get("/api/auth/me").session(session))
				.andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}")).andReturn();

		assertNull(refused.getRequest().getSession(false));
		verify(auditService).recordForActor(eq(AuditAction.AUTH_ACCESS_ATTEMPT), eq(AuditResult.DENIED), isNull(),
				anyString(), eq(Set.of()), eq(AuditMetadata.because(AuditReason.AUTHENTICATION_REQUIRED)));
		logoutAndAssertProtectedReadsDenied(session);
	}

	@Test
	void failedLoginDoesNotAuthenticateTheCsrfSessionOrPreventLogout() throws Exception {
		UserAccount account = createTestAccount();
		String wrongPassword = "incorrect-session-test-password";
		when(loginService.evaluate(account.getUserName(), wrongPassword))
				.thenReturn(new LoginAttempt(null, account.getUserID(), AuditReason.INVALID_CREDENTIALS));
		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);

		MvcResult failed = mockMvc.perform(post("/login").session(session)
				.header("X-CSRF-TOKEN", getToken(csrf)).param("username", account.getUserName())
				.param("password", wrongPassword)).andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}")).andReturn();

		assertFalse(session.isInvalid());
		assertNull(session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY));
		assertNull(sessionRegistry.getSessionInformation(session.getId()));
		assertNull(failed.getRequest().getUserPrincipal());
		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}"));
		verify(loginService).evaluate(account.getUserName(), wrongPassword);
		verifyNoInteractions(mfa);
		verify(auditService).recordForActor(eq(AuditAction.AUTH_LOGIN), eq(AuditResult.FAILURE), isNull(),
				anyString(), eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID()))),
				eq(AuditMetadata.because(AuditReason.INVALID_CREDENTIALS)));
		logoutAndAssertProtectedReadsDenied(session);
	}

	private UserAccount createTestAccount() {
		UUID userID = UUID.randomUUID();
		UserAccount account = new UserAccount(userID, "session-test-" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), UUID.randomUUID(), "Example", "Test", "test@example.invalid",
				"+12025550100");
		when(accountStore.findById(userID)).thenReturn(Optional.of(account));
		return account;
	}

	private MockHttpSession openAuthenticatedSession(UserAccount account) throws Exception {
		when(loginService.evaluate(account.getUserName(), PASSWORD))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		String anonymousSessionID = session.getId();

		MvcResult login = mockMvc.perform(post("/login").session(session)
				.with(request -> {
					request.setRequestedSessionId(anonymousSessionID);
					return request;
				})
				.header("X-CSRF-TOKEN", getToken(csrf)).param("username", account.getUserName())
				.param("password", PASSWORD)).andExpect(status().isOk()).andReturn();
		MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);
		assertNotNull(authenticated);
		assertNotEquals(anonymousSessionID, authenticated.getId());
		SecurityContext context = (SecurityContext) authenticated
				.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		assertNotNull(context);
		assertTrue(context.getAuthentication().isAuthenticated());
		assertNotNull(sessionRegistry.getSessionInformation(authenticated.getId()));
		verify(loginService).evaluate(account.getUserName(), PASSWORD);
		return authenticated;
	}

	private void assertCurrentUser(MockHttpSession session, UserAccount account) throws Exception {
		mockMvc.perform(get("/api/auth/me").session(session)).andExpect(status().isOk())
				.andExpect(jsonPath("$.userName").value(account.getUserName()));
	}

	private void logoutAndAssertProtectedReadsDenied(MockHttpSession session) throws Exception {
		MvcResult csrf = requestCsrf(session.isInvalid() ? null : session);
		MockHttpSession logoutSession = (MockHttpSession) csrf.getRequest().getSession(false);

		mockMvc.perform(post("/logout").session(logoutSession).header("X-CSRF-TOKEN", getToken(csrf)))
				.andExpect(status().isNoContent());

		assertTrue(logoutSession.isInvalid());
		MvcResult refused = mockMvc.perform(get("/api/auth/me").session(logoutSession))
				.andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}")).andReturn();
		assertNull(refused.getRequest().getSession(false));
	}

	private MvcResult requestCsrf(MockHttpSession session) throws Exception {
		var request = get("/api/auth/csrf");

		if (session != null) {
			request.session(session);
		}

		MvcResult result = mockMvc.perform(request).andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty()).andReturn();
		assertNotNull(result.getRequest().getSession(false));
		return result;
	}

	private String getToken(MvcResult result) throws Exception {
		return JsonPath.read(result.getResponse().getContentAsString(), "$.token");
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class SessionClockConfiguration {

		@Bean
		@Primary
		SessionLifetime controlledSessionLifetime(Clock clock) {
			return new SessionLifetime(clock);
		}
	}
}
