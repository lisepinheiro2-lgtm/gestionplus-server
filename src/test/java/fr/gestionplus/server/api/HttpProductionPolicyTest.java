package fr.gestionplus.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.GestionAuthenticationProvider;
import fr.gestionplus.server.auth.HttpSecurityConfig;
import fr.gestionplus.server.auth.LoginAttemptLimiter;
import fr.gestionplus.server.auth.LoginService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;

@WebMvcTest(CsrfController.class)
@ActiveProfiles("prod")
@Import({ HttpSecurityConfig.class, GestionAuthenticationProvider.class, LoginAttemptLimiter.class })
class HttpProductionPolicyTest {

	@Autowired
	private MockMvc mvc;
	@Autowired
	private Environment environment;

	@MockitoBean
	private AccountStore accounts;
	@MockitoBean
	private AuditService audit;
	@MockitoBean
	private AccountMfaService mfa;
	@MockitoBean
	private LoginService login;

	@Test
	void rejectsPlainHttpEvenWhenTheClientClaimsThatAProxyUsedHttps() throws Exception {
		mvc.perform(get("/api/auth/csrf").header("X-Forwarded-Proto", "https")
				.header("Forwarded", "for=192.0.2.1;proto=https"))
				.andExpect(status().isForbidden()).andExpect(content().json("{\"code\":\"HTTPS_REQUIRED\"}"));
		verify(audit).recordForActor(AuditAction.AUTH_ACCESS_ATTEMPT, AuditResult.DENIED, null, "127.0.0.1",
				Set.of(), AuditMetadata.because(AuditReason.INSECURE_TRANSPORT));
	}

	@Test
	void attributesAnInsecureRequestToTheExistingAuthenticatedSession() throws Exception {
		UserAccount account = new UserAccount(UUID.randomUUID(), "transport-test", UserAccountStatus.ACTIVE,
				Set.of(), UUID.randomUUID(), "Exemple", "Test", "test@example.invalid", "0600000000");
		var context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
		MockHttpSession session = new MockHttpSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);

		mvc.perform(get("/api/auth/csrf").session(session).header("X-User-ID", UUID.randomUUID()))
				.andExpect(status().isForbidden()).andExpect(content().json("{\"code\":\"HTTPS_REQUIRED\"}"));

		verify(audit).recordForActor(AuditAction.AUTH_ACCESS_ATTEMPT, AuditResult.DENIED, account.getUserID(),
				"127.0.0.1", Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID())),
				AuditMetadata.because(AuditReason.INSECURE_TRANSPORT));
	}

	@Test
	void allowsHttpsAndUsesTheConfiguredTransportAndCookiePolicy() throws Exception {
		mvc.perform(get("/api/auth/csrf").secure(true)).andExpect(status().isOk())
				.andExpect(jsonPath("$.token").isNotEmpty()).andExpect(header().exists("Strict-Transport-Security"));

		assertTrue(environment.getProperty("server.ssl.enabled", Boolean.class, false));
		assertTrue(environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false));
		assertTrue(environment.getProperty("server.servlet.session.cookie.http-only", Boolean.class, false));
		assertEquals("lax", environment.getProperty("server.servlet.session.cookie.same-site"));
		assertEquals("none", environment.getProperty("server.forward-headers-strategy"));
		assertEquals(Integer.valueOf(32), environment.getProperty("server.tomcat.max-parameter-count", Integer.class));
	}
}
