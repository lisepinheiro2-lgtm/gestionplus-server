package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;

class SessionLifetimeTest {

	private final Instant started = Instant.parse("2026-01-01T12:00:00Z");
	private final Clock clock = mock(Clock.class);
	private final SessionLifetime lifetime = new SessionLifetime(clock);

	@Test
	void reportsTheWarningTwoMinutesBeforeIdleExpiryAndRejectsAtTheDeadline() {
		when(clock.instant()).thenReturn(started);
		MockHttpSession session = new MockHttpSession();
		lifetime.start(session, true);

		var access = lifetime.access(session, started.plusSeconds(1799));
		assertNotNull(access);
		assertTrue(access.mfaVerified());
		assertEquals(started.plus(Duration.ofMinutes(30)), access.expiration().expiresAt());
		assertEquals(started.plus(Duration.ofMinutes(28)), access.expiration().warnAt());
		assertEquals(1800, session.getMaxInactiveInterval());
		assertNull(lifetime.access(session, started.plusSeconds(1800)));
	}

	@Test
	void activityExtendsIdleExpiryOnlyUntilTheAbsoluteDeadline() {
		when(clock.instant()).thenReturn(started);
		MockHttpSession session = new MockHttpSession();
		lifetime.start(session, false);

		for (int minutes = 20; minutes < 480; minutes += 20) {
			lifetime.touch(session, started.plus(Duration.ofMinutes(minutes)));
		}

		Instant absolute = started.plus(Duration.ofHours(8));
		var access = lifetime.access(session, absolute.minusSeconds(1));
		assertNotNull(access);
		assertEquals(absolute, access.expiration().expiresAt());
		assertEquals(absolute.minus(Duration.ofMinutes(2)), access.expiration().warnAt());
		assertNull(lifetime.access(session, absolute));

		lifetime.touch(session, absolute);
		assertNull(lifetime.access(session, absolute));
	}

	@Test
	void expiredOrInvalidatedSessionsCannotBeRevived() {
		when(clock.instant()).thenReturn(started);
		MockHttpSession session = new MockHttpSession();
		lifetime.start(session, false);

		Instant expiry = started.plus(Duration.ofMinutes(30));
		lifetime.touch(session, expiry);
		assertNull(lifetime.access(session, expiry));
		session.invalidate();
		lifetime.touch(session, expiry);
		assertNull(lifetime.access(session, expiry));
	}
}
