package fr.gestionplus.server.auth;

public interface LoginAttemptLimiter {

	Attempt begin(String userName, String address);

	boolean finish(Attempt attempt, boolean failed, boolean knownAccount);

	record Attempt(String accountKey, String addressKey, String reservationID) {
	}
}
