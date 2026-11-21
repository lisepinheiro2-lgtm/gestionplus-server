package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
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
}
