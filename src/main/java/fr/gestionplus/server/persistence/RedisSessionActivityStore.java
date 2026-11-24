package fr.gestionplus.server.persistence;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.DistributedSecurityUnavailableException;
import fr.gestionplus.server.auth.SessionActivityStore;

@Component
public final class RedisSessionActivityStore implements SessionActivityStore {

	private static final DefaultRedisScript<List> ACTIVITY = RedisSecuritySupport.script("session-activity");
	private static final DefaultRedisScript<List> VALIDATE_TOGETHER = RedisSecuritySupport
			.script("session-activity-together");
	private final RedisSecuritySupport redis;

	public RedisSessionActivityStore(RedisSecuritySupport redis) {
		this.redis = redis;
	}

	@Override
	public void start(String sessionID, boolean mfaVerified) {
		redis.execute(ACTIVITY, keys(sessionID), "start", mfaVerified ? "1" : "0");
	}

	@Override
	public State find(String sessionID) {
		List<?> result = redis.execute(ACTIVITY, keys(sessionID), "find", "0");

		if (((Number) result.getFirst()).longValue() == 0) {
			return null;
		}

		return new State(Instant.ofEpochMilli(((Number) result.get(1)).longValue()),
				Instant.ofEpochMilli(((Number) result.get(2)).longValue()),
				((Number) result.get(3)).longValue() == 1);
	}

	@Override
	public void touch(String sessionID, Instant requestStarted) {
		redis.execute(ACTIVITY, keys(sessionID), "touch", Long.toString(requestStarted.toEpochMilli()));
	}

	@Override
	public void end(String sessionID) {
		redis.execute(ACTIVITY, keys(sessionID), "end", "0");
	}

	private List<String> keys(String sessionID) {
		return List.of(redis.prefix("session-activity") + sessionID);
	}

	@Override
	public Optional<Instant> validateTogether(String firstSessionID, String secondSessionID) {

		if (firstSessionID == null || secondSessionID == null
				|| firstSessionID.isBlank() || secondSessionID.isBlank()
				|| firstSessionID.equals(secondSessionID)) {
			return Optional.empty();
		}

		String prefix = redis.prefix("session-activity");

		List<?> result = redis.execute(VALIDATE_TOGETHER,
				List.of(prefix + firstSessionID, prefix + secondSessionID));

		if (result.size() == 1 && result.getFirst() instanceof Number status
				&& status.longValue() == 0) {
			return Optional.empty();
		}

		if (result.size() != 2 || !(result.getFirst() instanceof Number status)
				|| status.longValue() != 1 || !(result.get(1) instanceof Number timestamp)) {
			throw new DistributedSecurityUnavailableException();
		}

		return Optional.of(Instant.ofEpochMilli(timestamp.longValue()));
	}
}
