
package fr.gestionplus.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.filter.FormContentFilter;

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
import fr.gestionplus.server.auth.GestionAuthenticationProvider.LoginAssurance;
import fr.gestionplus.server.auth.HttpSecurityConfig;
import fr.gestionplus.server.auth.LoginAttemptLimiter;
import fr.gestionplus.server.auth.LoginDelay;
import fr.gestionplus.server.auth.LoginService;
import fr.gestionplus.server.auth.LoginService.LoginAttempt;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

@WebMvcTest({ CsrfController.class, AuthSessionController.class })
@Import({ HttpSecurityConfig.class, GestionAuthenticationProvider.class, LoginAttemptLimiter.class,
		CsrfHttpTest.UnlistedController.class })
class CsrfHttpTest {

	@Autowired
	private MockMvc mockMvc;
	@Autowired
	private FilterChainProxy securityFilters;
	@Autowired
	@Qualifier("requestPolicyRegistration")
	private FilterRegistrationBean<?> requestPolicyRegistration;
	@Autowired
	@Qualifier("formContentRegistration")
	private FilterRegistrationBean<?> formContentRegistration;

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
    
	private final String userName = "alice";
	private final String password = "test-password";

	@BeforeEach
	void allowOrdinaryAccountsToLogInWithoutMfa() {
		when(mfa.verifyForLogin(any(UserAccount.class), nullable(String.class))).thenReturn(Verification.NOT_REQUIRED);
	}

	@Test
	void aNewControllerRemainsDeniedUntilTheRouteIsExplicitlyAuthorized() throws Exception {
		mockMvc.perform(get("/api/unlisted")).andExpect(status().isUnauthorized())
				.andExpect(content().json("{\"code\":\"AUTHENTICATION_REQUIRED\"}"));

		UserAccount account = createTestAccount();
		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		MvcResult login = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", getToken(csrf))
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();

		mockMvc.perform(get("/api/unlisted").session((MockHttpSession) login.getRequest().getSession(false)))
				.andExpect(status().isForbidden()).andExpect(content().json("{\"code\":\"FORBIDDEN\"}"));
	}

	@Test
	void oversizedLoginIsRejectedBeforePasswordOrMfaVerification() throws Exception {
		mockMvc.perform(post("/login").content(new byte[16385])).andExpect(status().is(413))
				.andExpect(content().json("{\"code\":\"PAYLOAD_TOO_LARGE\"}"));
		verifyNoInteractions(loginService, mfa);
	}

	@Test
	void loadsTheSessionBeforeRequestPolicyAndKeepsItBeforeCsrfAndAuthentication() {
		var filters = securityFilters.getFilters("/login");
		int policyIndex = filters.indexOf(requestPolicyRegistration.getFilter());
		assertFalse(requestPolicyRegistration.isEnabled());
		assertTrue(policyIndex > 0);
		int formIndex = filters.indexOf(formContentRegistration.getFilter());
		assertFalse(formContentRegistration.isEnabled());
		assertTrue(formContentRegistration.getFilter() instanceof FormContentFilter);
		assertTrue(formIndex > policyIndex);
		assertEquals(1, filters.stream().filter(FormContentFilter.class::isInstance).count());
		assertTrue(filters.subList(formIndex + 1, filters.size()).stream().anyMatch(CsrfFilter.class::isInstance));
		assertTrue(filters.subList(0, policyIndex).stream()
				.anyMatch(SecurityContextHolderFilter.class::isInstance));
		assertTrue(filters.subList(policyIndex + 1, filters.size()).stream().anyMatch(CsrfFilter.class::isInstance));
		assertTrue(filters.subList(policyIndex + 1, filters.size()).stream()
				.anyMatch(UsernamePasswordAuthenticationFilter.class::isInstance));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void attributesOversizedRequestsOnlyToAnAuthenticatedSession(boolean authenticated) throws Exception {
		UserAccount account = createTestAccount();
		MockHttpSession session = authenticated ? authenticatedSession(account) : new MockHttpSession();

		mockMvc.perform(post("/login").session(session).header("X-User-ID", account.getUserID())
				.header("X-Username", userName).content(new byte[16385]))
				.andExpect(status().is(413)).andExpect(content().json("{\"code\":\"PAYLOAD_TOO_LARGE\"}"));

		verifyRequestPolicyRefusal(authenticated ? account : null, AuditReason.INVALID_INPUT);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void attributesInvalidFormsWithoutTrustingCredentialsOrExposingSecrets(boolean authenticated) throws Exception {
		UserAccount account = createTestAccount();
		MockHttpSession session = authenticated ? authenticatedSession(account) : new MockHttpSession();

		mockMvc.perform(post("/login").session(session).param("username", userName)
				.param("password", "private-first-password", "private-second-password").param("totp", "012345"))
				.andExpect(status().isBadRequest()).andExpect(content().json("{\"code\":\"INVALID_REQUEST\"}"));

		verifyRequestPolicyRefusal(authenticated ? account : null, AuditReason.INVALID_INPUT);
	}

	private MockHttpSession authenticatedSession(UserAccount account) throws Exception {
		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		MvcResult login = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", getToken(csrf))
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();
		return (MockHttpSession) login.getRequest().getSession(false);
	}

	private void verifyRequestPolicyRefusal(UserAccount account, AuditReason reason) {
		UUID actorID = account == null ? null : account.getUserID();
		Set<AuditTarget> targets = actorID == null ? Set.of()
				: Set.of(new AuditTarget(AuditTargetType.ACCOUNT, actorID));
		verify(auditService).recordForActor(eq(AuditAction.AUTH_ACCESS_ATTEMPT), eq(AuditResult.DENIED), eq(actorID),
				anyString(), eq(targets), eq(AuditMetadata.because(reason)));
	}

	@TestComponent
	@RestController
	static class UnlistedController {

		@GetMapping("/api/unlisted")
		String unlisted() {
			return "private-test-value";
		}
	}

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

		verify(auditService).recordForActor(eq(AuditAction.AUTH_ACCESS_ATTEMPT), eq(AuditResult.DENIED), isNull(),
				anyString(), eq(Set.of()), eq(AuditMetadata.because(AuditReason.CSRF_REJECTED)));
	}

	@Test
	void acceptsValidLoginAndStoresSession() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));

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
		verify(auditService).recordForActor(eq(AuditAction.AUTH_LOGIN), eq(AuditResult.SUCCESS),
				eq(account.getUserID()),
				anyString(), eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID()))),
				eq(AuditMetadata.empty()));
	}

	@Test
	void rejectsIncorrectPassword() throws Exception {

		String attemptedName = "wrong-password-user";
		when(loginService.evaluate(attemptedName, "wrong-password"))
				.thenReturn(new LoginAttempt(null, null, AuditReason.INVALID_CREDENTIALS));

		MvcResult csrfResult = requestCsrf(null);
		String token = getToken(csrfResult);

		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);

		mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", token).param("username", attemptedName)
				.param("password", "wrong-password")).andExpect(status().isUnauthorized());
	}

	@Test
	void unknownAndDisabledAccountsReturnTheSameResponseWithDistinctInternalReasons() throws Exception {
		UUID disabledId = UUID.randomUUID();
		when(loginService.evaluate("unknown", password))
				.thenReturn(new LoginAttempt(null, null, AuditReason.UNKNOWN_ACCOUNT));
		when(loginService.evaluate("disabled", password))
				.thenReturn(new LoginAttempt(null, disabledId, AuditReason.DISABLED_ACCOUNT));

		MvcResult unknown = rejectedLogin("unknown");
		MvcResult disabled = rejectedLogin("disabled");

		assertEquals(unknown.getResponse().getContentAsString(), disabled.getResponse().getContentAsString());
		verify(auditService).recordForActor(eq(AuditAction.AUTH_LOGIN), eq(AuditResult.FAILURE), isNull(), anyString(),
				eq(Set.of()), eq(AuditMetadata.because(AuditReason.UNKNOWN_ACCOUNT)));
		verify(auditService).recordForActor(eq(AuditAction.AUTH_LOGIN), eq(AuditResult.FAILURE), isNull(), anyString(),
				eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, disabledId))),
				eq(AuditMetadata.because(AuditReason.DISABLED_ACCOUNT)));
	}

	@Test
	void failedMandatoryAuditPreventsASuccessfulLogin() throws Exception {
		UserAccount account = createTestAccount();
		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
		doThrow(new IllegalStateException("Audit indisponible")).when(auditService)
				.recordForActor(eq(AuditAction.AUTH_LOGIN), eq(AuditResult.SUCCESS), eq(account.getUserID()),
						anyString(), eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID()))),
						eq(AuditMetadata.empty()));

		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", getToken(csrf))
				.param("username", userName).param("password", password)).andExpect(status().isServiceUnavailable());

		assertTrue(session.isInvalid());
	}

	private MvcResult rejectedLogin(String attemptedName) throws Exception {
		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		return mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", getToken(csrf))
				.param("username", attemptedName).param("password", password)).andExpect(status().isUnauthorized())
				.andReturn();
	}

	@Test
	void requiresFreshCsrfTokenAfterLogin() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));

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
		UserAccount account = new UserAccount(UUID.randomUUID(), userName, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), UUID.randomUUID(), "Exemple", "Test", "test@example.invalid", "0600000000");
		when(accountStore.findById(account.getUserID())).thenReturn(Optional.of(account));
		return account;
	}

	@Test
	void recognizesAuthenticatedUserOnNextRequest() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));

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
	void rejectsAFormerSessionEvenWhenTheAccountIsActiveAgain() throws Exception {

		UserAccount account = createTestAccount();
		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
		MvcResult csrfResult = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrfResult.getRequest().getSession(false);
		MvcResult login = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", getToken(csrfResult))
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();
		MockHttpSession authenticatedSession = (MockHttpSession) login.getRequest().getSession(false);

		UserAccount reactivated = new UserAccount(account.getUserID(), account.getUserName(), UserAccountStatus.ACTIVE,
				Set.of(), account.getEmployeeID(), account.getLastName(), account.getFirstName(), account.getEmail(),
				account.getPhoneNumber(), account.getSessionVersion() + 1);
		when(accountStore.findById(account.getUserID())).thenReturn(Optional.of(reactivated));

		mockMvc.perform(get("/api/auth/me").session(authenticatedSession)).andExpect(status().isUnauthorized());
		assertTrue(authenticatedSession.isInvalid());
	}

	@Test
	void requiresTotpForTheAccountAndKeepsOnlyAssuranceInTheSession() throws Exception {
		UserAccount account = createTestAccount();
		String attemptedName = "mfa-required-user";
		String code = "012345";
		when(loginService.evaluate(attemptedName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
		when(mfa.verifyForLogin(account, code)).thenReturn(Verification.VERIFIED);
		when(mfa.verifyForLogin(account, null)).thenReturn(Verification.DENIED);
		when(mfa.requiresVerification(account.getUserID())).thenReturn(true);

		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		MvcResult login = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", getToken(csrf))
				.param("username", attemptedName).param("password", password).param("totp", code))
				.andExpect(status().isOk()).andReturn();
		MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);
		SecurityContext context = (SecurityContext) authenticated
				.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);
		assertEquals(new LoginAssurance(true), context.getAuthentication().getDetails());

		mockMvc.perform(get("/api/auth/session").session(authenticated)).andExpect(status().isOk())
				.andExpect(jsonPath("$.expiresAt").exists()).andExpect(jsonPath("$.warnAt").exists());
		rejectedLogin(attemptedName);
		verify(auditService).recordForActor(eq(AuditAction.AUTH_LOGIN), eq(AuditResult.FAILURE), isNull(),
				anyString(), eq(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID()))),
				eq(AuditMetadata.because(AuditReason.MFA_REQUIRED)));
	}

	@Test
	void rejectsAPasswordOnlySessionWhenMfaBecomesMandatory() throws Exception {
		UserAccount account = createTestAccount();
		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
		MvcResult csrf = requestCsrf(null);
		MockHttpSession session = (MockHttpSession) csrf.getRequest().getSession(false);
		MvcResult login = mockMvc.perform(post("/login").session(session).header("X-CSRF-TOKEN", getToken(csrf))
				.param("username", userName).param("password", password)).andExpect(status().isOk()).andReturn();
		MockHttpSession authenticated = (MockHttpSession) login.getRequest().getSession(false);

		when(mfa.requiresVerification(account.getUserID())).thenReturn(true);

		mockMvc.perform(get("/api/auth/me").session(authenticated)).andExpect(status().isUnauthorized());
		assertTrue(authenticated.isInvalid());
	}

	@Test
	void refusesCurrentUserWithoutSession() throws Exception {
		mockMvc.perform(get("/api/auth/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void logoutInvalidatesCurrentSession() throws Exception {

		UserAccount account = createTestAccount();

		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));
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

		when(loginService.evaluate(userName, password))
				.thenReturn(new LoginAttempt(account, account.getUserID(), null));

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
