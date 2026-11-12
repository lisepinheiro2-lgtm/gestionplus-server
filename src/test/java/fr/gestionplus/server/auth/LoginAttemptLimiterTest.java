package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.GestionAuthenticationProvider.RejectedLogin;

class LoginAttemptLimiterTest {

	private final Clock clock = mock(Clock.class);
	private final Instant start = Instant.parse("2026-01-01T12:00:00Z");
	private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

	@BeforeEach
	void setUp() {
		at(0);
	}

	@Test
	void delaysFreshIdentifiersFromAnAddressWithManyFailures() {

		for (int failure = 0; failure < 10; failure++) {
			var attempt = limiter.begin("account" + failure, "192.0.2.1");
			assertNotNull(attempt);
			assertFalse(limiter.finish(attempt, true, false));
		}

		when(clock.instant()).thenReturn(start.plusMillis(100));
		assertNull(limiter.begin("fresh", "192.0.2.1"));
		assertNotNull(limiter.begin("elsewhere", "192.0.2.2"));

		when(clock.instant()).thenReturn(start.plusMillis(500));
		assertNotNull(limiter.begin("fresh", "192.0.2.1"));
		assertNotNull(limiter.begin("account0", "192.0.2.2"));
	}

	@Test
	void rejectsConcurrentAttemptsAndGraduallyIncreasesTheRetryDelay() {
		var first = limiter.begin("target", "192.0.2.1");
		assertNotNull(first);
		assertNull(limiter.begin("target", "192.0.2.2"));
		assertFalse(limiter.finish(first, true, true));
		when(clock.instant()).thenReturn(start.plusMillis(199));
		assertNull(limiter.begin("target", "192.0.2.2"));

		when(clock.instant()).thenReturn(start.plusMillis(200));
		var second = limiter.begin("target", "192.0.2.2");
		assertNotNull(second);
		assertFalse(limiter.finish(second, true, true));
		when(clock.instant()).thenReturn(start.plusMillis(599));
		assertNull(limiter.begin("target", "192.0.2.2"));
		when(clock.instant()).thenReturn(start.plusMillis(600));
		assertNotNull(limiter.begin("target", "192.0.2.2"));
	}

	@Test
	void combinesAccountAndAddressWeightsInTheLoginDelay() {

		for (int failure = 0; failure < 10; failure++) {
			var attempt = limiter.begin("account" + failure, "192.0.2.1");
			assertNotNull(attempt);
			assertFalse(limiter.finish(attempt, true, true));
		}

		when(clock.instant()).thenReturn(start.plusMillis(500));
		assertNull(limiter.begin("account0", "192.0.2.1"));
		assertNotNull(limiter.begin("unaffected", "192.0.2.1"));
		assertNotNull(limiter.begin("account0", "192.0.2.2"));
	}

	@Test
	void discardsFailuresOutsideTheSlidingWindow() {

		for (int failure = 0; failure < 9; failure++) {
			at(failure * 3L);
			var attempt = limiter.begin("target", "192.0.2.1");
			assertNotNull(attempt);
			assertFalse(limiter.finish(attempt, true, true));
		}

		at(900);
		var attempt = limiter.begin("target", "192.0.2.1");
		assertNotNull(attempt);
		assertFalse(limiter.finish(attempt, true, true));
	}

	@Test
	void doesNotDenyANewLoginWhenUnknownNamesFillTheTrackingCapacity() {

		for (int failure = 0; failure < 8192; failure++) {
			at(3L * failure);
			var attempt = limiter.begin("unknown-" + failure, "192.0.2.1");
			assertNotNull(attempt);
			assertFalse(limiter.finish(attempt, true, false));
		}

		assertNotNull(limiter.begin("new-account", "192.0.2.2"));
	}

	@Test
	void auditsTheRestrictionSeparatelyFromTheGenericLoginFailure() {
		UUID userID = UUID.randomUUID();
		LoginService login = mock(LoginService.class);
		when(login.evaluate("target", "incorrect"))
				.thenReturn(new LoginService.LoginAttempt(null, userID, AuditReason.INVALID_CREDENTIALS));
		GestionAuthenticationProvider provider = new GestionAuthenticationProvider(
				login, mock(AccountMfaService.class), limiter);
		var credentials = UsernamePasswordAuthenticationToken.unauthenticated("target", "incorrect");
		credentials.setDetails(new GestionAuthenticationProvider.LoginDetails("192.0.2.1", null));

		for (int index = 0; index < 9; index++) {
			at(index * 3L);
			assertFalse(
					assertThrows(RejectedLogin.class, () -> provider.authenticate(credentials)).restrictionStarted());
		}

		at(27);
		RejectedLogin threshold = assertThrows(RejectedLogin.class, () -> provider.authenticate(credentials));
		RejectedLogin limited = assertThrows(RejectedLogin.class, () -> provider.authenticate(credentials));
		assertTrue(threshold.restrictionStarted());
		assertEquals(threshold.getMessage(), limited.getMessage());

		AuditService audit = mock(AuditService.class);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("192.0.2.1");
		new HttpSecurityAudit(audit).loginFailed(request, threshold);

		Set<AuditTarget> targets = Set.of(new AuditTarget(AuditTargetType.ACCOUNT, userID));
		verify(audit).recordForActor(AuditAction.AUTH_LOGIN, AuditResult.FAILURE, null, "192.0.2.1", targets,
				AuditMetadata.because(AuditReason.INVALID_CREDENTIALS));
		verify(audit).recordForActor(AuditAction.AUTH_LOGIN_RESTRICTION, AuditResult.DENIED, null, "192.0.2.1", targets,
				AuditMetadata.because(AuditReason.RATE_LIMITED));
	}

	@Test
	void letsUntrackedAttemptsThroughWhileTheAddressRegistryHasRoom() {

		var first = limiter.begin("unknown-0", "192.0.2.1");
		assertNotNull(first);
		assertFalse(limiter.finish(first, true, false));

		var second = limiter.begin("unknown-1", "192.0.2.2");
		assertNotNull(second);
		assertFalse(limiter.finish(second, true, false));
	}

	@Test
	void throttlesUntrackedAttemptsGloballyWhenTheAddressRegistryIsSaturated() {
		saturateAddresses();

		var first = limiter.begin("untracked-0", "203.0.113.1");
		assertNotNull(first);
		assertFalse(limiter.finish(first, true, false));

		assertNull(limiter.begin("untracked-1", "203.0.113.2"));
		assertNull(limiter.begin("untracked-2", "203.0.113.3"));

		at(5);
		var second = limiter.begin("untracked-3", "203.0.113.4");
		assertNotNull(second);
		assertFalse(limiter.finish(second, true, false));
		assertNull(limiter.begin("untracked-4", "203.0.113.5"));

		at(10);
		var third = limiter.begin("untracked-5", "203.0.113.6");
		assertNotNull(third);
		assertFalse(limiter.finish(third, true, false));
	}

	@Test
	void exemptsKnownAccountsWithRecordedFailuresFromTheGlobalThrottle() {
		var known = limiter.begin("victim", "192.0.2.1");
		assertNotNull(known);
		assertFalse(limiter.finish(known, true, true));

		saturateAddresses();

		var passage = limiter.begin("untracked-0", "203.0.113.1");
		assertNotNull(passage);
		assertFalse(limiter.finish(passage, true, false));

		at(1);
		assertNotNull(limiter.begin("victim", "203.0.113.2"));
		assertNull(limiter.begin("unknown-9000", "203.0.113.3"));
	}

	private void saturateAddresses() {

		for (int failure = 0; failure < 8192; failure++) {
			var attempt = limiter.begin("unknown-" + failure, "192.0.2." + failure);
			assertNotNull(attempt);
			assertFalse(limiter.finish(attempt, true, false));
		}

	}

	@Test
	void recordsFailuresForTrackedAddressesWhileTheRegistryIsSaturated() {
		var seed = limiter.begin("seed", "192.0.2.0");
		assertNotNull(seed);
		assertFalse(limiter.finish(seed, true, false));

		saturateAddresses();

		for (int failure = 1; failure < 10; failure++) {
			at(failure);
			var attempt = limiter.begin("unknown-" + failure, "192.0.2.0");
			assertNotNull(attempt);
			assertFalse(limiter.finish(attempt, true, false));
		}

		assertNull(limiter.begin("fresh", "192.0.2.0"));

		at(10);
		assertNotNull(limiter.begin("fresh", "192.0.2.0"));
	}

	@Test
	void doesNotLetExpiredAddressFailuresInflateTheDelay() {

		for (int failure = 0; failure < 10; failure++) {
			var attempt = limiter.begin("account" + failure, "192.0.2.9");
			assertNotNull(attempt);
			assertFalse(limiter.finish(attempt, true, false));
		}

		at(901);
		var fresh = limiter.begin("target", "192.0.2.8");
		assertNotNull(fresh);
		assertFalse(limiter.finish(fresh, true, false));

		when(clock.instant()).thenReturn(start.plusMillis(901_400));
		assertNotNull(limiter.begin("target", "192.0.2.9"));
	}

	private void at(long seconds) {
		when(clock.instant()).thenReturn(start.plusSeconds(seconds));
	}
}
