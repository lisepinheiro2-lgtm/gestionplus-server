package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;

import fr.gestionplus.server.account.AccountStore;

class EnrollmentSessionFilterTest {

	private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
	private final AccountStore accountStore = mock(AccountStore.class);
	private final AccountMfaService accountMfaService = mock(AccountMfaService.class);
	private final HttpSecurityAudit audit = mock(HttpSecurityAudit.class);
	private final RequestRateLimiter rateLimiter = mock(RequestRateLimiter.class);
	private final Clock clock = mock(Clock.class);
	private final SessionLifetime lifetime = new SessionLifetime(clock);
	private final CurrentAccountSessionFilter filter = new CurrentAccountSessionFilter(accountStore, audit, lifetime,
			accountMfaService, rateLimiter);
	private final EnrollmentPrincipal principal = new EnrollmentPrincipal(UUID.randomUUID(), 7, NOW.plusSeconds(600));
	private final MockHttpSession session = new MockHttpSession();

	@BeforeEach
	void prepare() {
		when(clock.instant()).thenReturn(NOW);
		when(accountStore.findById(principal.userID())).thenReturn(Optional.of(new UserAccount(principal.userID(),
				"fictitious", UserAccountStatus.ACTIVE, Set.of(), null, "Fictitious", "Enrollment",
				"enrollment@example.invalid", "+12025550100", 7)));
		when(rateLimiter.acquireForAccount(anyString(), any(), anyInt(), any()))
				.thenReturn(new RequestRateLimiter.Decision(true, Duration.ZERO));
		SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
				principal, null, List.of(new SimpleGrantedAuthority(EnrollmentPrincipal.AUTHORITY))));
	}

	@AfterEach
	void clear() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/auth/mfa/enrollment", "/api/auth/mfa/enrollment/complete",
			"/api/auth/mfa/%65nrollment", "/logout" })
	void onlyAllowsEnrollmentPostsAndLogout(String path) throws Exception {
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request("POST", path), new MockHttpServletResponse(), chain);
		assertNotNull(chain.getRequest());
	}

	@Test
	void allowsCsrfWithoutRenewingTheAbsoluteDeadline() throws Exception {
		when(clock.instant()).thenReturn(NOW.plusSeconds(599));
		MockFilterChain first = new MockFilterChain();
		filter.doFilter(request("GET", "/api/auth/csrf"), new MockHttpServletResponse(), first);
		assertNotNull(first.getRequest());
		when(clock.instant()).thenReturn(NOW.plusSeconds(600));
		MockHttpServletResponse expired = new MockHttpServletResponse();
		MockFilterChain second = new MockFilterChain();
		filter.doFilter(request("GET", "/api/auth/csrf"), expired, second);
		assertEquals(401, expired.getStatus());
		assertNull(second.getRequest());
		assertTrue(session.isInvalid());
	}

	@ParameterizedTest
	@ValueSource(strings = { "/api/auth/me", "/api/auth/session", "/api/structures", "/unknown" })
	void refusesBusinessAndUnknownRoutesWithoutConvertingTheContext(String path) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request("GET", path), response, chain);
		assertEquals(403, response.getStatus());
		assertNull(chain.getRequest());
		assertFalse(session.isInvalid());
	}

	@Test
	void invalidatesAContextWhoseAccountNoLongerExists() throws Exception {
		when(accountStore.findById(principal.userID())).thenReturn(Optional.empty());
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request("POST", "/api/auth/mfa/enrollment"), response, new MockFilterChain());
		assertEquals(401, response.getStatus());
		assertTrue(session.isInvalid());
	}

	@Test
	void limitsConfirmationOutsideTheEnrollmentTransactionAndRoundsRetryAfterUp() throws Exception {
		when(rateLimiter.acquireForAccount("mfa-enrollment", principal.userID(), 5, EnrollmentPrincipal.VALIDITY))
				.thenReturn(new RequestRateLimiter.Decision(false, Duration.ofMillis(1500)));
		MockHttpServletResponse response = new MockHttpServletResponse();
		MockFilterChain chain = new MockFilterChain();
		filter.doFilter(request("POST", "/api/auth/mfa/enrollment/complete"), response, chain);
		assertEquals(429, response.getStatus());
		assertEquals("2", response.getHeader("Retry-After"));
		assertNull(chain.getRequest());
		verifyNoInteractions(accountMfaService);
	}

	private MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setSession(session);
		return request;
	}
}
