
package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;

class SessionRegistryIdentityTest {

	@Test
	void findsAllSessionsForSameUserId() {

		UUID userID = UUID.randomUUID();
		UUID employeeID = UUID.randomUUID();

		UserAccount account1 = new UserAccount(userID, "alice", UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE),
				employeeID);
		UserAccount account2 = new UserAccount(userID, "alice", UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE),
				employeeID);

		SessionRegistryImpl registry = new SessionRegistryImpl();

		registry.registerNewSession("session-A", account1);
		registry.registerNewSession("session-B", account2);

		int total = registry.getAllSessions(account1, false).size();
		assertEquals(2, total);
	}
}
