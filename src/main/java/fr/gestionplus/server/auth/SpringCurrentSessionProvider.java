package fr.gestionplus.server.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import fr.gestionplus.server.account.CurrentSessionProvider;
import jakarta.servlet.http.HttpSession;

@Component
public class SpringCurrentSessionProvider implements CurrentSessionProvider {

	private final SessionLifetime lifetime;

	public SpringCurrentSessionProvider(SessionLifetime lifetime) {
		this.lifetime = lifetime;
	}

	@Override
	public String getCurrentSessionIDHash() {
		HttpSession session = currentSession();
		return session == null ? null : SessionIDHasher.hash(session.getId());
	}

	@Override
	public String getActiveSessionIDHash() {
		HttpSession session = currentSession();
		return lifetime.access(session, lifetime.now()) == null ? null : SessionIDHasher.hash(session.getId());
	}

	private HttpSession currentSession() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		return attributes instanceof ServletRequestAttributes servlet
				? servlet.getRequest().getSession(false) : null;
	}
}
