package fr.gestionplus.server.persistence;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.DistributedSecurityUnavailableException;

@Component
public final class RedisSecuritySupport {

	private static final DefaultRedisScript<Long> TIME = new DefaultRedisScript<>(
			"local t = redis.call('TIME'); return t[1] * 1000 + math.floor(t[2] / 1000)", Long.class);
	private static final DefaultRedisScript<List> CONFIGURATION = new DefaultRedisScript<>(
			"redis.call('SET', KEYS[1], ARGV[1], 'NX'); return {redis.call('GET', KEYS[1]) == ARGV[1] and 1 or 0}",
			List.class);
	private final StringRedisTemplate redis;
	private final String namespace;
	private final SecretKeySpec key;

	public RedisSecuritySupport(StringRedisTemplate redis,
			@Value("${gestionplus.cluster.namespace}") String namespace,
			@Value("${gestionplus.cluster.rate-limit-key}") String encodedKey) {
		this.redis = Objects.requireNonNull(redis);

		if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{2,47}")) {
			throw new IllegalArgumentException("L’espace de noms du serveur doit être défini pour cet environnement.");
		}

		this.namespace = namespace;

		try {
			byte[] decoded = Base64.getDecoder().decode(encodedKey);

			try {

				if (decoded.length < 32 || decoded.length > 64) {
					throw new IllegalArgumentException();
				}

				this.key = new SecretKeySpec(decoded, "HmacSHA256");
			} finally {
				Arrays.fill(decoded, (byte) 0);
			}

		} catch (RuntimeException invalid) {
			throw new IllegalArgumentException(
					"La clé partagée de limitation doit contenir de 32 à 64 octets en Base64.");
		}

	}

	public String prefix(String scope) {
		return "gestionplus:" + namespace + ":{" + scope + "}:";
	}

	public String digest(String scope, String value) {

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal(
					(scope + ":" + (value == null ? "" : value)).getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException failure) {
			throw new DistributedSecurityUnavailableException();
		}

	}

	public String addressKey(String scope, String address) {

		if (address == null || address.isBlank()) {
			return digest(scope, "unknown");
		}

		try {
			return digest(scope, HexFormat.of().formatHex(InetAddress.ofLiteral(address).getAddress()));
		} catch (IllegalArgumentException invalid) {
			throw new DistributedSecurityUnavailableException();
		}

	}

	public List<?> execute(DefaultRedisScript<List> script, List<String> keys, String... arguments) {

		try {
			List<?> result = redis.execute(script, keys, (Object[]) arguments);

			if (result == null || result.isEmpty()) {
				throw new DistributedSecurityUnavailableException();
			}

			return result;
		} catch (RuntimeException failure) {
			throw new DistributedSecurityUnavailableException();
		}

	}

	public Instant now() {

		try {
			Long milliseconds = redis.execute(TIME, List.of());

			if (milliseconds == null) {
				throw new DistributedSecurityUnavailableException();
			}

			return Instant.ofEpochMilli(milliseconds);
		} catch (RuntimeException failure) {
			throw new DistributedSecurityUnavailableException();
		}

	}

	public void verifyConfiguration(String... sharedKeys) {
		String fingerprint = digest("shared-configuration", String.join(":", sharedKeys));
		List<?> result = execute(CONFIGURATION, List.of(prefix("configuration") + "fingerprint"), fingerprint);

		if (((Number) result.getFirst()).longValue() != 1) {
			throw new IllegalStateException("Les instances ne partagent pas la même configuration cryptographique.");
		}

	}

	public Clock clock() {
		return clock(ZoneOffset.UTC);
	}

	private Clock clock(ZoneId zone) {
		return new Clock() {
			@Override
			public ZoneId getZone() {
				return zone;
			}

			@Override
			public Clock withZone(ZoneId zone) {
				Objects.requireNonNull(zone);
				return RedisSecuritySupport.this.clock(zone);
			}

			@Override
			public Instant instant() {
				return now();
			}
		};
	}

	public static DefaultRedisScript<List> script(String name) {
		DefaultRedisScript<List> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource("redis/" + name + ".lua"));
		script.setResultType(List.class);
		return script;
	}
}
