package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import fr.gestionplus.server.auth.DistributedSecurityUnavailableException;
import fr.gestionplus.server.auth.RequestRateLimiter;
import fr.gestionplus.server.support.SecurityTestRedis;

class RedisRequestRateLimiterIntegrationTest {

	private static final LettuceConnectionFactory FIRST = SecurityTestRedis.connectionFactory();
	private static final LettuceConnectionFactory SECOND = SecurityTestRedis.connectionFactory();
	private static final String SCOPE = "public-invitation";
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private RedisSecuritySupport support;
	private StringRedisTemplate template;
	private RequestRateLimiter first;
	private RequestRateLimiter second;

	@BeforeEach
	void setUp() {
		String namespace = "quota-test-" + UUID.randomUUID().toString().replace("-", "");
		template = SecurityTestRedis.template(FIRST);
		support = new RedisSecuritySupport(template, namespace, SecurityTestRedis.rateKey());
		first = new RedisRequestRateLimiter(support);
		second = new RedisRequestRateLimiter(new RedisSecuritySupport(
				SecurityTestRedis.template(SECOND), namespace, SecurityTestRedis.rateKey()));
	}

	@AfterAll
	static void closeConnections() {
		FIRST.destroy();
		SECOND.destroy();
	}

	@Test
	void keepsAccountQuotasSharedAndSeparateFromIpAddressParsing() {
		UUID userID = UUID.randomUUID();
		assertTrue(first.acquireForAccount("mfa-enrollment", userID, 1, Duration.ofMinutes(10)).allowed());
		assertFalse(second.acquireForAccount("mfa-enrollment", userID, 1, Duration.ofMinutes(10)).allowed());
		assertTrue(second.acquireForAccount("mfa-enrollment", UUID.randomUUID(), 1, Duration.ofMinutes(10)).allowed());
	}

	@Test
	void neverAdmitsMoreThanThirtyConcurrentRequestsAcrossTwoClients() {

		try (var workers = Executors.newFixedThreadPool(12)) {
			var results = new ArrayList<CompletableFuture<RequestRateLimiter.Decision>>();

			for (int index = 0; index < 80; index++) {
				RequestRateLimiter limiter = index % 2 == 0 ? first : second;
				results.add(CompletableFuture.supplyAsync(
						() -> limiter.acquire(SCOPE, "192.0.2.1", 30, WINDOW), workers));
			}

			assertEquals(30,
					results.stream().map(CompletableFuture::join).filter(RequestRateLimiter.Decision::allowed).count());
		}

	}

	@Test
	void keepsTheQuotaAfterAClientIsRecreated() {
		assertTrue(first.acquire(SCOPE, "192.0.2.1", 1, WINDOW).allowed());
		assertFalse(second.acquire(SCOPE, "192.0.2.1", 1, WINDOW).allowed());
		assertFalse(new RedisRequestRateLimiter(support).acquire(SCOPE, "192.0.2.1", 1, WINDOW).allowed());
	}

	@Test
	void separatesAddressesAndCanonicalizesIpv6AndMappedIpv4() {
		assertTrue(first.acquire(SCOPE, "2001:db8::1", 1, WINDOW).allowed());
		assertFalse(second.acquire(SCOPE, "2001:0db8:0:0:0:0:0:1", 1, WINDOW).allowed());
		assertTrue(first.acquire(SCOPE, "192.0.2.1", 1, WINDOW).allowed());
		assertFalse(second.acquire(SCOPE, "::ffff:192.0.2.1", 1, WINDOW).allowed());
		assertTrue(second.acquire(SCOPE, "192.0.2.2", 1, WINDOW).allowed());
	}

	@Test
	void returnsARetryDelayWithoutExtendingTheBlockedWindow() {
		assertTrue(first.acquire(SCOPE, "192.0.2.1", 1, WINDOW).allowed());
		String key = support.prefix(SCOPE) + support.addressKey(SCOPE, "192.0.2.1");
		Double expiry = template.opsForZSet().score(support.prefix(SCOPE) + "addresses", key);
		var rejected = second.acquire(SCOPE, "192.0.2.1", 1, WINDOW);
		assertFalse(rejected.allowed());
		assertTrue(rejected.retryAfter().isPositive());
		assertTrue(rejected.retryAfter().compareTo(WINDOW) <= 0);
		assertEquals(expiry, template.opsForZSet().score(support.prefix(SCOPE) + "addresses", key));
		assertTrue(template.getExpire(key) > 0);
	}

	@Test
	void discardsAnExpiredWindowBeforeAdmittingANewRequest() {
		String key = support.prefix(SCOPE) + support.addressKey(SCOPE, "192.0.2.1");
		long now = support.now().toEpochMilli();
		template.opsForZSet().add(key, "expired-request", now - WINDOW.toMillis() - 1);
		template.opsForZSet().add(support.prefix(SCOPE) + "addresses", key, now - 1);
		assertTrue(first.acquire(SCOPE, "192.0.2.1", 1, WINDOW).allowed());
		assertEquals(1, template.opsForZSet().zCard(key));
	}

	@Test
	void refusesNewAddressesWhenTheRegistryIsFull() {
		long expiration = support.now().plus(WINDOW).toEpochMilli();
		var entries = new java.util.HashSet<org.springframework.data.redis.core.ZSetOperations.TypedTuple<String>>();

		for (int index = 0; index < 8192; index++) {
			entries.add(org.springframework.data.redis.core.ZSetOperations.TypedTuple.of("fictitious-" + index,
					(double) expiration));
		}

		template.opsForZSet().add(support.prefix(SCOPE) + "addresses", entries);
		assertThrows(DistributedSecurityUnavailableException.class,
				() -> first.acquire(SCOPE, "192.0.2.1", 30, WINDOW));
		assertEquals(8192, template.opsForZSet().zCard(support.prefix(SCOPE) + "addresses"));
	}

	@Test
	void refusesInvalidConfigurationAndNonLiteralAddresses() {
		assertThrows(IllegalArgumentException.class, () -> first.acquire("invalid:scope", "192.0.2.1", 30, WINDOW));
		assertThrows(IllegalArgumentException.class, () -> first.acquire(SCOPE, "192.0.2.1", 0, WINDOW));
		assertThrows(DistributedSecurityUnavailableException.class,
				() -> first.acquire(SCOPE, "untrusted.example.invalid", 30, WINDOW));
	}
}
