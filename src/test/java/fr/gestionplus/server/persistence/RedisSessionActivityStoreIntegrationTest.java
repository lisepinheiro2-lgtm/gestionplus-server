package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import fr.gestionplus.server.auth.SessionActivityStore;
import fr.gestionplus.server.support.SecurityTestRedis;

class RedisSessionActivityStoreIntegrationTest {

	private static final LettuceConnectionFactory CONNECTION = SecurityTestRedis.connectionFactory();
	private RedisSecuritySupport support;
	private SessionActivityStore first;
	private SessionActivityStore second;
	private String sessionID;

	@BeforeEach
	void setUp() {
		String namespace = "activity-test-" + UUID.randomUUID().toString().replace("-", "");
		support = new RedisSecuritySupport(SecurityTestRedis.template(CONNECTION), namespace,
				SecurityTestRedis.rateKey());
		first = new RedisSessionActivityStore(support);
		second = new RedisSessionActivityStore(support);
		sessionID = UUID.randomUUID().toString();
	}

	@AfterAll
	static void closeConnection() {
		CONNECTION.destroy();
	}

	@Test
	void sharesTheInitialActivityAndMfaAssurance() {
		first.start(sessionID, true);
		var state = second.find(sessionID);
		assertNotNull(state);
		assertTrue(state.mfaVerified());
		assertEquals(state.startedAt(), state.lastActivity());
	}

	@Test
	void neverMovesActivityBackwardsWhenAnOlderRequestCompletesLater() {
		first.start(sessionID, false);
		var started = first.find(sessionID);
		second.touch(sessionID, support.now());
		var newer = first.find(sessionID);
		first.touch(sessionID, started.startedAt());
		assertEquals(newer.lastActivity(), second.find(sessionID).lastActivity());
	}

	@Test
	void neverRecreatesASessionWhenARequestCompletesAfterLogout() {
		first.start(sessionID, true);
		var requestStarted = support.now();
		second.end(sessionID);
		first.touch(sessionID, requestStarted);
		assertNull(second.find(sessionID));
	}

	@Test
	void enforcesTheAbsoluteDeadlineEvenWhenRecentActivityExists() {
		first.start(sessionID, true);
		long old = support.now().minus(Duration.ofHours(8)).toEpochMilli();
		SecurityTestRedis.template(CONNECTION).opsForHash().put(
				support.prefix("session-activity") + sessionID, "started", Long.toString(old));
		assertNull(second.find(sessionID));
	}

	@Test
	void neverRenewsAnExpiredIdleSession() {
		first.start(sessionID, true);
		long old = support.now().minus(Duration.ofMinutes(31)).toEpochMilli();
		SecurityTestRedis.template(CONNECTION).opsForHash().put(
				support.prefix("session-activity") + sessionID, "activity", Long.toString(old));
		first.touch(sessionID, support.now());
		assertNull(second.find(sessionID));
	}

	@Test
	void validatesTwoSessionsWithTheRedisInstantWithoutRefreshingTheirActivity() {
		String other = UUID.randomUUID().toString();
		first.start(sessionID, true);
		second.start(other, true);
		var initialFirst = first.find(sessionID);
		var initialSecond = second.find(other);
		Instant before = support.now();
		Instant authorizedAt = second.validateTogether(sessionID, other).orElseThrow();
		assertTrue(!authorizedAt.isBefore(before) && !authorizedAt.isAfter(support.now()));
		assertEquals(initialFirst, first.find(sessionID));
		assertEquals(initialSecond, second.find(other));
	}

	@Test
	void refusesAnUnverifiedSessionOrOneEndedByAnotherInstance() {
		String other = UUID.randomUUID().toString();
		first.start(sessionID, true);
		second.start(other, false);
		assertTrue(first.validateTogether(sessionID, other).isEmpty());
		second.start(other, true);
		first.end(other);
		assertTrue(second.validateTogether(sessionID, other).isEmpty());
	}

	@Test
	void enforcesBothDeadlinesEvenWhenActivityKeysRemainInRedis() {
		String other = UUID.randomUUID().toString();
		first.start(sessionID, true);
		second.start(other, true);
		var hashes = SecurityTestRedis.template(CONNECTION).opsForHash();
		String key = support.prefix("session-activity") + other;
		Instant now = support.now();
		hashes.put(key, "started", Long.toString(now.minus(Duration.ofHours(8)).toEpochMilli()));
		assertTrue(first.validateTogether(sessionID, other).isEmpty());
		hashes.put(key, "started", Long.toString(now.minus(Duration.ofHours(1)).toEpochMilli()));
		hashes.put(key, "activity", Long.toString(now.minus(Duration.ofMinutes(30)).toEpochMilli()));
		assertTrue(first.validateTogether(sessionID, other).isEmpty());
	}
}
