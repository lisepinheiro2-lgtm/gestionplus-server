package fr.gestionplus.server.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.DistributedSecurityUnavailableException;
import fr.gestionplus.server.auth.LoginAttemptLimiter;

@Component
public final class RedisLoginAttemptLimiter implements LoginAttemptLimiter {

	private static final DefaultRedisScript<List> ATTEMPTS = RedisSecuritySupport.script("login-attempts");
	private final RedisSecuritySupport redis;
	private final List<String> keys;

	public RedisLoginAttemptLimiter(RedisSecuritySupport redis) {
		this.redis = redis;
		String prefix = redis.prefix("login");
		this.keys = List.of(prefix + "accounts", prefix + "account-expirations", prefix + "unknown-accounts",
				prefix + "addresses", prefix + "address-expirations", prefix + "untracked");
	}

	@Override
	public Attempt begin(String userName, String address) {
		String accountKey = redis.digest("login-account", userName);
		String addressKey = redis.addressKey("login-address", address);
		String reservationID = UUID.randomUUID().toString();
		List<?> result = redis.execute(ATTEMPTS, keys, "begin", accountKey, addressKey, reservationID, "0", "0");
		return status(result) == 1 ? new Attempt(accountKey, addressKey, reservationID) : null;
	}

	@Override
	public boolean finish(Attempt attempt, boolean failed, boolean knownAccount) {
		List<?> result = redis.execute(ATTEMPTS, keys, "finish", attempt.accountKey(), attempt.addressKey(),
				attempt.reservationID(), failed ? "1" : "0", knownAccount ? "1" : "0");
		return status(result) == 1;
	}

	private long status(List<?> result) {
		long status = ((Number) result.getFirst()).longValue();

		if (status < 0) {
			throw new DistributedSecurityUnavailableException();
		}

		return status;
	}
}
