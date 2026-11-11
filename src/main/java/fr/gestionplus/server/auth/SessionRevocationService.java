package fr.gestionplus.server.auth;

import java.util.List;
import java.util.Objects;

import org.springframework.security.core.session.SessionInformation;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Service
public class SessionRevocationService {

	private final SessionRegistry registry;

	public SessionRevocationService(SessionRegistry registry) {
		this.registry = Objects.requireNonNull(registry);
	}

	public void revokeAllSessionsAfterCommit(UserAccount account) {
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {

				for (SessionInformation session : registry.getAllSessions(account, false)) {

					if (session.getPrincipal() instanceof UserAccount principal
							&& principal.getSessionVersion() <= account.getSessionVersion()) {
						session.expireNow();
					}

				}

			}
		});
	}

	public void revokeAllSessions(UserAccount account) {

		List<SessionInformation> sessions = registry.getAllSessions(account, false);

		for (SessionInformation session : sessions) {
			session.expireNow();
		}

	}
}
