package fr.gestionplus.server.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.GestionAuthenticationProvider;
import fr.gestionplus.server.auth.HttpSecurityConfig;
import fr.gestionplus.server.auth.LoginAttemptLimiter;
import fr.gestionplus.server.auth.LoginService;

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
