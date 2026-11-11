package fr.gestionplus.server.auth;

import java.io.Serializable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.web.util.WebUtils;

import jakarta.servlet.http.HttpSession;

public final class SessionLifetime {

	public static final String STATUS_PATH = "/api/auth/session";
	public static final String REQUEST_EXPIRATION = "gestionplus.session.expiration";
	private static final String STATE = SessionLifetime.class.getName() + ".state";
	private static final Duration IDLE = Duration.ofMinutes(30);
	private static final Duration ABSOLUTE = Duration.ofHours(8);
	private static final Duration WARNING = Duration.ofMinutes(2);

	private final Clock clock;

	public SessionLifetime(Clock clock) {
		this.clock = clock;
	}

	public void start(HttpSession session, boolean mfaVerified) {
		Instant now = clock.instant();

		synchronized (WebUtils.getSessionMutex(session)) {
			session.setMaxInactiveInterval(Math.toIntExact(IDLE.toSeconds()));
			session.setAttribute(STATE, new State(now, now, mfaVerified));
		}

	}

	Instant now() {
		return clock.instant();
	}

	Access access(HttpSession session, Instant now) {

		if (session == null) {
			return null;
		}

		try {

			synchronized (WebUtils.getSessionMutex(session)) {

				if (session.getAttribute(STATE) instanceof State state && now.isBefore(deadline(state))) {
					Instant expiresAt = deadline(state);
					return new Access(state.mfaVerified(), new Expiration(expiresAt, expiresAt.minus(WARNING)));
				}

			}

		} catch (IllegalStateException invalidated) {
			return null;
		}

		return null;
	}

	void touch(HttpSession session, Instant requestStarted) {

		try {

			synchronized (WebUtils.getSessionMutex(session)) {

				if (session.getAttribute(STATE) instanceof State state && requestStarted.isBefore(deadline(state))
						&& requestStarted.isAfter(state.lastActivity())) {
					session.setAttribute(STATE, new State(state.startedAt(), requestStarted, state.mfaVerified()));
				}

			}

		} catch (IllegalStateException invalidated) {
			return;
		}

	}

	private Instant deadline(State state) {
		Instant idle = state.lastActivity().plus(IDLE);
		Instant absolute = state.startedAt().plus(ABSOLUTE);
		return idle.isBefore(absolute) ? idle : absolute;
	}

	public record Expiration(Instant expiresAt, Instant warnAt) {
	}

	record Access(boolean mfaVerified, Expiration expiration) {
	}

	private record State(Instant startedAt, Instant lastActivity, boolean mfaVerified) implements Serializable {
	}
}
