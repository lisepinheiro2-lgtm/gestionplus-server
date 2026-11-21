package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Base64;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import fr.gestionplus.server.support.SecurityTestRedis;

class RedisSecuritySupportTest {

	private static final LettuceConnectionFactory CONNECTION = SecurityTestRedis.connectionFactory();

	@AfterAll
	static void closeConnection() {
		CONNECTION.destroy();
	}

	@Test
	void acceptsTheSameCryptographicConfigurationAcrossInstances() {
		String namespace = namespace();
		var first = support(namespace, SecurityTestRedis.rateKey());
		var second = support(namespace, SecurityTestRedis.rateKey());
		first.verifyConfiguration("fictitious-mfa-key", "fictitious-delivery-key", "fictitious-challenge-key");
		assertDoesNotThrow(() -> second.verifyConfiguration(
				"fictitious-mfa-key", "fictitious-delivery-key", "fictitious-challenge-key"));
	}

	@Test
	void refusesAnInstanceWhoseSharedKeysDiffer() {
		String namespace = namespace();
		var first = support(namespace, SecurityTestRedis.rateKey());
		first.verifyConfiguration("mfa-a", "delivery", "challenge");
		assertThrows(IllegalStateException.class,
				() -> support(namespace, SecurityTestRedis.rateKey()).verifyConfiguration("mfa-b", "delivery",
						"challenge"));
		String different = Base64.getEncoder().encodeToString(new byte[32]);
		assertThrows(IllegalStateException.class,
				() -> support(namespace, different).verifyConfiguration("mfa-a", "delivery", "challenge"));
	}

	@Test
	void refusesMissingNamespaceAndWeakOrMalformedKeys() {
		assertThrows(IllegalArgumentException.class, () -> support("", SecurityTestRedis.rateKey()));
		assertThrows(IllegalArgumentException.class, () -> support("invalid:namespace", SecurityTestRedis.rateKey()));
		assertThrows(IllegalArgumentException.class, () -> support(namespace(), "not-base64"));
		assertThrows(IllegalArgumentException.class,
				() -> support(namespace(), Base64.getEncoder().encodeToString(new byte[16])));
	}

	private RedisSecuritySupport support(String namespace, String key) {
		return new RedisSecuritySupport(SecurityTestRedis.template(CONNECTION), namespace, key);
	}

	private String namespace() {
		return "config-test-" + UUID.randomUUID().toString().replace("-", "");
	}
}
