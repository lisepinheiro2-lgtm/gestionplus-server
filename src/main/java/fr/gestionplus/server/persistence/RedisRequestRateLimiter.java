package fr.gestionplus.server.persistence;

import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.DistributedSecurityUnavailableException;
import fr.gestionplus.server.auth.RequestRateLimiter;

@Component
public final class RedisRequestRateLimiter implements RequestRateLimiter {

	private static final DefaultRedisScript<List> ACQUIRE = RedisSecuritySupport.script("request-rate-limit");
	private final RedisSecuritySupport redis;

	public RedisRequestRateLimiter(RedisSecuritySupport redis) {
		this.redis = redis;
	}

	@Override
	public Decision acquire(String scope, String address, int limit, Duration window) {
		return acquireWithKey(scope, redis.addressKey(scope, address), limit, window);
	}

	@Override
	public Decision acquireForAccount(String scope, UUID userID, int limit, Duration window) {
		Objects.requireNonNull(userID, "L’identifiant du compte est obligatoire.");
		return acquireWithKey(scope, redis.digest(scope, "account:" + userID), limit, window);
	}

	private Decision acquireWithKey(String scope, String subjectKey, int limit, Duration window) {

		if (scope == null || !scope.matches("[a-z][a-z0-9-]{1,63}") || limit < 1 || limit > 10000
				|| window == null || window.compareTo(Duration.ofSeconds(1)) < 0
				|| window.compareTo(Duration.ofDays(1)) > 0) {
			throw new IllegalArgumentException("La configuration de limitation des requêtes est invalide.");
		}

		String prefix = redis.prefix(scope);
		List<?> result = redis.execute(ACQUIRE,
				List.of(prefix + "addresses", prefix + subjectKey),
				Integer.toString(limit), Long.toString(window.toMillis()), UUID.randomUUID().toString(), "8192");
		long status = ((Number) result.get(0)).longValue();

		if (status < 0) {
			throw new DistributedSecurityUnavailableException();
		}

		return new Decision(status == 1, Duration.ofMillis(((Number) result.get(1)).longValue()));
	}
}
