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
	private final SessionActivityStore shared;

	public SessionLifetime(Clock clock) {
		this(clock, null);
	}

	public SessionLifetime(Clock clock, SessionActivityStore shared) {
		this.clock = clock;
		this.shared = shared;
	}

	public void start(HttpSession session, boolean mfaVerified) {
		Instant now = clock.instant();

		synchronized (WebUtils.getSessionMutex(session)) {
			session.setMaxInactiveInterval(Math.toIntExact(IDLE.toSeconds()));

			if (shared == null) {
				session.setAttribute(STATE, new State(now, now, mfaVerified));
			} else {
				shared.start(session.getId(), mfaVerified);
				session.setAttribute(STATE, Boolean.TRUE);
			}

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

				State state = state(session);

				if (state != null && now.isBefore(deadline(state))) {
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

			if (shared != null) {

				if (Boolean.TRUE.equals(session.getAttribute(STATE))) {
					shared.touch(session.getId(), requestStarted);
				}

				return;
			}

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

	public void end(HttpSession session) {

		if (session != null && shared != null) {
			shared.end(session.getId());
		}

	}

	private State state(HttpSession session) {
		Object value = session.getAttribute(STATE);

		if (shared == null) {
			return value instanceof State state ? state : null;
		}

		SessionActivityStore.State state = Boolean.TRUE.equals(value) ? shared.find(session.getId()) : null;
		return state == null ? null : new State(state.startedAt(), state.lastActivity(), state.mfaVerified());
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
