package fr.gestionplus.server.auth;

import java.util.List;
import java.util.Objects;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;

@Service
public class SessionRevocationService {

	private final SessionRegistry registry;

	public SessionRevocationService(SessionRegistry registry) {
		this.registry = Objects.requireNonNull(registry);
	}

	public void revokeAllSessions(UserAccount account) {

		List<SessionInformation> sessions = registry.getAllSessions(account, false);

		for (SessionInformation session : sessions)
			session.expireNow();
	}
}
