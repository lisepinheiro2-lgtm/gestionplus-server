package fr.gestionplus.server.persistence;

import java.time.Instant;
import java.util.List;

import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.auth.SessionActivityStore;

@Component
public final class RedisSessionActivityStore implements SessionActivityStore {

	private static final DefaultRedisScript<List> ACTIVITY = RedisSecuritySupport.script("session-activity");
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
}
