package fr.gestionplus.server.auth;

import java.util.Date;
import java.util.List;
import java.util.Objects;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;

public final class DistributedSessionRegistry implements SessionRegistry {

	private final FindByIndexNameSessionRepository<? extends Session> sessions;
	private final SessionActivityStore activity;

	public DistributedSessionRegistry(FindByIndexNameSessionRepository<? extends Session> sessions,
			SessionActivityStore activity) {
		this.sessions = Objects.requireNonNull(sessions);
		this.activity = Objects.requireNonNull(activity);
	}

	@Override
	public List<Object> getAllPrincipals() {
		throw new UnsupportedOperationException("La liste globale des comptes connectés n’est pas disponible.");
	}

	@Override
	public List<SessionInformation> getAllSessions(Object principal, boolean includeExpiredSessions) {

		if (!(principal instanceof UserAccount account)) {
			return List.of();
		}

		return sessions.findByPrincipalName(account.getUserID().toString()).values().stream()
				.map(this::information).filter(Objects::nonNull).toList();
	}

	@Override
	public SessionInformation getSessionInformation(String sessionId) {
		Session session = sessions.findById(sessionId);
		return session == null ? null : information(session);
	}

	@Override
	public void refreshLastRequest(String sessionId) {
	}

	@Override
	public void registerNewSession(String sessionId, Object principal) {
	}

	@Override
	public void removeSessionInformation(String sessionId) {
	}

	private SessionInformation information(Session session) {
		Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

		if (!(value instanceof SecurityContext context) || context.getAuthentication() == null) {
			return null;
		}
		Object principal = context.getAuthentication().getPrincipal();
		if (!(principal instanceof UserAccount) && !(principal instanceof EnrollmentPrincipal)) {
			return null;
		}

		return new SessionInformation(principal, session.getId(), Date.from(session.getLastAccessedTime())) {
			@Override
			public void expireNow() {
				activity.end(getSessionId());
				sessions.deleteById(getSessionId());
				super.expireNow();
			}
		};
	}
}
