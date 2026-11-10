
package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.core.session.SessionRegistryImpl;

class SessionRevocationServiceTest {

	@Test
	void revokesOnlyTargetAccountSessions() {

		UUID aliceID = UUID.randomUUID();

		UserAccount alice1 = new UserAccount(aliceID, "alice", UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE),
				UUID.randomUUID(), "Exemple", "Alice", "alice@example.invalid", "0600000000");
		UserAccount alice2 = new UserAccount(aliceID, "alice", UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE),
				alice1.getEmployeeID(),
				alice1.getLastName(), alice1.getFirstName(), alice1.getEmail(), alice1.getPhoneNumber());
		UserAccount bob = new UserAccount(UUID.randomUUID(), "bob", UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE),
				UUID.randomUUID(), "Exemple", "Bob", "bob@example.invalid", "0600000001");

		SessionRegistryImpl registry = new SessionRegistryImpl();

		registry.registerNewSession("alice-PC", alice1);
		registry.registerNewSession("alice-laptop", alice2);
		registry.registerNewSession("bob-PC", bob);

		SessionRevocationService service = new SessionRevocationService(registry);

		assertEquals(2, registry.getAllSessions(alice1, false).size());
		assertEquals(1, registry.getAllSessions(bob, false).size());

		service.revokeAllSessions(alice1);

		assertEquals(0, registry.getAllSessions(alice1, false).size());
		assertTrue(registry.getSessionInformation("alice-PC").isExpired());
		assertTrue(registry.getSessionInformation("alice-laptop").isExpired());
		assertEquals(1, registry.getAllSessions(bob, false).size());
		assertFalse(registry.getSessionInformation("bob-PC").isExpired());
	}
}
