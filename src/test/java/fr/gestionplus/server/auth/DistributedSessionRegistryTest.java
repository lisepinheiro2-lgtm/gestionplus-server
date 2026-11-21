package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.MapSession;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class DistributedSessionRegistryTest {

	private final FindByIndexNameSessionRepository<MapSession> sessions = mock();
	private final SessionActivityStore activity = mock();
	private final DistributedSessionRegistry registry = new DistributedSessionRegistry(sessions, activity);

	@Test
	void findsSessionsByUserIdAndPreservesEachSecurityVersion() {
		UUID id = UUID.randomUUID();
		UserAccount original = account(id, 1);
		UserAccount changed = account(id, 2);
		MapSession oldSession = session(original);
		MapSession newSession = session(changed);
		when(sessions.findByPrincipalName(id.toString())).thenReturn(Map.of(oldSession.getId(), oldSession,
				newSession.getId(), newSession));
		var found = registry.getAllSessions(changed, false);
		assertEquals(2, found.size());
		assertEquals(Set.of(1L, 2L),
				found.stream().map(value -> ((UserAccount) value.getPrincipal()).getSessionVersion())
						.collect(java.util.stream.Collectors.toSet()));
	}

	@Test
	void removesTheActivityBeforeDeletingTheSharedSession() {
		MapSession session = session(account(UUID.randomUUID(), 1));
		when(sessions.findById(session.getId())).thenReturn(session);
		var information = registry.getSessionInformation(session.getId());
		assertNotNull(information);
		information.expireNow();
		var order = inOrder(activity, sessions);
		order.verify(activity).end(session.getId());
		order.verify(sessions).deleteById(session.getId());
	}

	@Test
	void neverTreatsAnAnonymousCsrfSessionAsAuthenticated() {
		MapSession session = new MapSession();
		when(sessions.findById(session.getId())).thenReturn(session);
		assertNull(registry.getSessionInformation(session.getId()));
	}

	@Test
	void revokesOlderSessionsAfterCommitWhilePreservingANewerVersion() {
		UUID id = UUID.randomUUID();
		MapSession older = session(account(id, 2));
		MapSession newer = session(account(id, 4));
		when(sessions.findByPrincipalName(id.toString()))
				.thenReturn(Map.of(older.getId(), older, newer.getId(), newer));
		TransactionSynchronizationManager.initSynchronization();

		try {
			new SessionRevocationService(registry).revokeAllSessionsAfterCommit(account(id, 3));
			verify(sessions, never()).deleteById(anyString());
			TransactionSynchronizationManager.getSynchronizations().forEach(TransactionSynchronization::afterCommit);
			verify(sessions).deleteById(older.getId());
			verify(sessions, never()).deleteById(newer.getId());
		} finally {
			TransactionSynchronizationManager.clearSynchronization();
		}

	}

	@Test
	void doesNotProvideAGlobalDirectoryOfConnectedUsers() {
		assertThrows(UnsupportedOperationException.class, registry::getAllPrincipals);
	}

	private MapSession session(UserAccount account) {
		MapSession session = new MapSession();
		session.setLastAccessedTime(Instant.now());
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of())));
		return session;
	}

	private UserAccount account(UUID id, long version) {
		return new UserAccount(id, "fictitious-cluster-user", UserAccountStatus.ACTIVE, Set.of(), UUID.randomUUID(),
				"Fictitious", "Test", "cluster@example.invalid", "+12025550100", version);
	}
}
