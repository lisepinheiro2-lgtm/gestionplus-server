package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.persistence.RedisLoginAttemptLimiter;
import fr.gestionplus.server.persistence.RedisSecuritySupport;
import fr.gestionplus.server.support.SecurityTestRedis;
import tools.jackson.databind.json.JsonMapper;

class LoginAttemptLimiterTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final LettuceConnectionFactory FIRST = SecurityTestRedis.connectionFactory();
	private static final LettuceConnectionFactory SECOND = SecurityTestRedis.connectionFactory();
	private StringRedisTemplate template;
	private RedisSecuritySupport support;
	private LoginAttemptLimiter first;
	private LoginAttemptLimiter second;

	@BeforeEach
	void setUp() {
		String namespace = "login-test-" + UUID.randomUUID().toString().replace("-", "");
		template = SecurityTestRedis.template(FIRST);
		support = new RedisSecuritySupport(template, namespace, SecurityTestRedis.rateKey());
		first = new RedisLoginAttemptLimiter(support);
		second = new RedisLoginAttemptLimiter(new RedisSecuritySupport(
				SecurityTestRedis.template(SECOND), namespace, SecurityTestRedis.rateKey()));
	}

	@AfterAll
	static void closeConnections() {
		FIRST.destroy();
		SECOND.destroy();
	}

	@Test
	void reservesOnlyOneAttemptAcrossInstances() {

		try (var workers = Executors.newFixedThreadPool(8)) {
			var attempts = new ArrayList<CompletableFuture<LoginAttemptLimiter.Attempt>>();

			for (int index = 0; index < 16; index++) {
				LoginAttemptLimiter limiter = index % 2 == 0 ? first : second;
				attempts.add(CompletableFuture.supplyAsync(() -> limiter.begin("target", "192.0.2.1"), workers));
			}

			assertEquals(1, attempts.stream().map(CompletableFuture::join).filter(java.util.Objects::nonNull).count());
		}

	}

	@Test
	void sharesTheRestrictionAfterTenFailures() {
		seedAccount("target", 9, 10000, true);
		var attempt = second.begin("target", "192.0.2.1");
		assertNotNull(attempt);
		assertTrue(second.finish(attempt, true, true));
		assertNull(first.begin("target", "192.0.2.2"));
	}

	@Test
	void preservesFailuresWhenAConnectionSucceeds() {
		seedAccount("target", 9, 10000, true);
		var successful = first.begin("target", "192.0.2.1");
		assertNotNull(successful);
		assertFalse(first.finish(successful, false, false));
		var failed = second.begin("target", "192.0.2.2");
		assertNotNull(failed);
		assertTrue(second.finish(failed, true, true));
	}

	@Test
	void discardsFailuresOutsideTheSlidingWindow() {
		seedAccount("target", 10, 901000, true);
		var attempt = second.begin("target", "192.0.2.1");
		assertNotNull(attempt);
		assertFalse(second.finish(attempt, true, true));
	}

	@Test
	void delaysAnAccountRegardlessOfTheInstanceOrAddress() {
		seedAccount("target", 10, 0, true);
		assertNull(second.begin("target", "192.0.2.2"));
	}

	@Test
	void combinesFailuresFromOneAddressAcrossAccounts() {
		long now = support.now().toEpochMilli();
		String address = support.addressKey("login-address", "192.0.2.1");
		Map<String, Object> history = Map.of("failures", java.util.Collections.nCopies(9, now - 10000));
		template.opsForHash().put(support.prefix("login") + "addresses", address, JSON.writeValueAsString(history));
		template.opsForZSet().add(support.prefix("login") + "address-expirations", address, now + 900000);
		var attempt = first.begin("first-account", "192.0.2.1");
		assertNotNull(attempt);
		assertFalse(first.finish(attempt, true, false));
		assertNull(second.begin("another-account", "192.0.2.1"));
		assertNotNull(second.begin("another-account", "192.0.2.2"));
	}

	@Test
	void recoversAfterAnInstanceLosesItsReservation() {
		var old = first.begin("target", "192.0.2.1");
		assertNotNull(old);
		String key = support.prefix("login") + "accounts";
		var history = (tools.jackson.databind.node.ObjectNode) JSON.readTree(
				template.<String, String>opsForHash().get(key, old.accountKey()));
		history.put("leaseUntil", support.now().toEpochMilli() - 1);
		template.opsForHash().put(key, old.accountKey(), JSON.writeValueAsString(history));
		var replacement = second.begin("target", "192.0.2.2");
		assertNotNull(replacement);
		assertThrows(DistributedSecurityUnavailableException.class, () -> first.finish(old, true, true));
		assertNull(first.begin("target", "192.0.2.3"));
		assertFalse(second.finish(replacement, false, false));
	}

	@Test
	void pausesWithoutCheckingCredentialsWhenTheQuotaRejectsTheAttempt() {
		var reservation = first.begin("target", "192.0.2.1");
		assertNotNull(reservation);
		LoginService login = mock(LoginService.class);
		LoginDelay delay = mock(LoginDelay.class);
		GestionAuthenticationProvider provider = new GestionAuthenticationProvider(
				login, mock(AccountMfaService.class), second, delay);
		var credentials = UsernamePasswordAuthenticationToken.unauthenticated("target", "incorrect");
		credentials.setDetails(new GestionAuthenticationProvider.LoginDetails("192.0.2.2", null));
		var rejected = assertThrows(GestionAuthenticationProvider.RejectedLogin.class,
				() -> provider.authenticate(credentials));
		assertEquals(AuditReason.RATE_LIMITED, rejected.reason());
		verify(delay).pause();
		verify(login, never()).evaluate(any(), any());
	}

	@Test
	void doesNotCountAnUnavailableCredentialStoreAsABadPassword() {
		LoginService login = mock(LoginService.class);
		when(login.evaluate("target", "candidate")).thenThrow(new IllegalStateException());
		GestionAuthenticationProvider provider = new GestionAuthenticationProvider(
				login, mock(AccountMfaService.class), first, mock(LoginDelay.class));
		var credentials = UsernamePasswordAuthenticationToken.unauthenticated("target", "candidate");
		credentials.setDetails(new GestionAuthenticationProvider.LoginDetails("192.0.2.1", null));
		var rejected = assertThrows(GestionAuthenticationProvider.RejectedLogin.class,
				() -> provider.authenticate(credentials));
		assertEquals(AuditReason.OPERATION_FAILED, rejected.reason());
		assertNotNull(second.begin("target", "192.0.2.1"));
	}

	@Test
	void neverStoresTheRawUserNameOrAddress() {
		var attempt = first.begin("private-test-name", "192.0.2.1");
		assertNotNull(attempt);
		assertFalse(first.finish(attempt, true, true));
		String accounts = template.opsForHash().entries(support.prefix("login") + "accounts").toString();
		String addresses = template.opsForHash().entries(support.prefix("login") + "addresses").toString();
		assertFalse(accounts.contains("private-test-name"));
		assertFalse(addresses.contains("192.0.2.1"));
	}

	@Test
	void boundsUnknownHistoriesWithoutBlockingAllNewAccounts() {
		fillRegistry(false, false);
		assertNotNull(first.begin("new-account", "192.0.2.2"));
		assertEquals(8192, template.opsForHash().size(support.prefix("login") + "accounts"));
	}

	@Test
	void preservesKnownHistoriesWhenTheAccountRegistryIsFull() {
		fillRegistry(false, true);
		assertNull(second.begin("new-account", "192.0.2.2"));
		assertEquals(8192, template.opsForHash().size(support.prefix("login") + "accounts"));
	}

	@Test
	void sharesTheGlobalThrottleWhenTheAddressRegistryIsFull() {
		fillRegistry(true, false);
		var admitted = first.begin("first-untracked", "203.0.113.1");
		assertNotNull(admitted);
		assertFalse(first.finish(admitted, true, false));
		assertNull(second.begin("second-untracked", "203.0.113.2"));
		seedAccount("known-account", 1, 10000, true);
		assertNotNull(second.begin("known-account", "203.0.113.3"));
		template.opsForValue().set(support.prefix("login") + "untracked",
				Long.toString(support.now().toEpochMilli() - 5001));
		assertNotNull(second.begin("later-untracked", "203.0.113.4"));
		assertEquals(8192, template.opsForHash().size(support.prefix("login") + "addresses"));
	}

	private void fillRegistry(boolean addresses, boolean known) {
		String prefix = support.prefix("login");
		String body = "local t=redis.call('TIME'); local now=t[1]*1000+math.floor(t[2]/1000); "
				+ "for i=1,8192 do local id='fictitious-'..i; "
				+ "redis.call('HSET',KEYS[1],id,cjson.encode({failures={now-10000},known=ARGV[1]=='1'})); "
				+ "redis.call('ZADD',KEYS[2],now+900000,id); "
				+ "if ARGV[1]=='0' then redis.call('ZADD',KEYS[3],now+900000,id); end; end; return 8192";
		template.execute(new org.springframework.data.redis.core.script.DefaultRedisScript<>(body, Long.class),
				List.of(prefix + (addresses ? "addresses" : "accounts"),
						prefix + (addresses ? "address-expirations" : "account-expirations"),
						prefix + "unknown-accounts"),
				known || addresses ? "1" : "0");
	}

	private void seedAccount(String name, int failures, long ageMilliseconds, boolean known) {
		long now = support.now().toEpochMilli();
		String id = support.digest("login-account", name);
		Map<String, Object> history = new LinkedHashMap<>();
		history.put("failures", java.util.Collections.nCopies(failures, now - ageMilliseconds));
		history.put("known", known);
		template.opsForHash().put(support.prefix("login") + "accounts", id, JSON.writeValueAsString(history));
		template.opsForZSet().add(support.prefix("login") + "account-expirations", id, now + 900000);
	}
}
