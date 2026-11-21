package fr.gestionplus.server.auth;

import java.time.Instant;

public interface SessionActivityStore {

	void start(String sessionID, boolean mfaVerified);

	State find(String sessionID);

	void touch(String sessionID, Instant requestStarted);

	void end(String sessionID);

	record State(Instant startedAt, Instant lastActivity, boolean mfaVerified) {
	}
}
