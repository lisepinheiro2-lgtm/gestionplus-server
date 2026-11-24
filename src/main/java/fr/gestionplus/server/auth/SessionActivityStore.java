package fr.gestionplus.server.auth;

import java.time.Instant;
import java.util.Optional;

public interface SessionActivityStore {

	void start(String sessionID, boolean mfaVerified);

	State find(String sessionID);

	void touch(String sessionID, Instant requestStarted);

	void end(String sessionID);

	Optional<Instant> validateTogether(String firstSessionID, String secondSessionID);

	record State(Instant startedAt, Instant lastActivity, boolean mfaVerified) {
	}
}
