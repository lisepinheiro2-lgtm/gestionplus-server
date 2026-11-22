package fr.gestionplus.server.auth;

import java.time.Duration;
import java.util.UUID;

public interface RequestRateLimiter {

	Decision acquire(String scope, String address, int limit, Duration window);

	Decision acquireForAccount(String scope, UUID userID, int limit, Duration window);

	record Decision(boolean allowed, Duration retryAfter) {
	}
}
