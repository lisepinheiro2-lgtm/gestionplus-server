package fr.gestionplus.server.auth;

import java.time.Duration;

public interface RequestRateLimiter {

	Decision acquire(String scope, String address, int limit, Duration window);

	record Decision(boolean allowed, Duration retryAfter) {
	}
}
