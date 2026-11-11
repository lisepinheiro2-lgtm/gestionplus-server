
package fr.gestionplus.server.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.auth.UserRole;

class StructureMembershipTest {

	@Test
	void keepsAccountAndStructureIdentity() {

		UUID userID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();
		StructureMembership membership = new StructureMembership(userID, structureID, Set.of(UserRole.EMPLOYEE));

		assertEquals(userID, membership.getUserID());
		assertEquals(structureID, membership.getStructureID());
		assertEquals(Set.of(UserRole.EMPLOYEE), membership.getRoles());
	}

	@Test
	void allowsDifferentRolesInDifferentStructures() {

		UUID userID = UUID.randomUUID();
		StructureMembership first = new StructureMembership(userID, UUID.randomUUID(), Set.of(UserRole.ADMIN));
		StructureMembership second = new StructureMembership(userID, UUID.randomUUID(), Set.of(UserRole.EMPLOYEE));

		assertEquals(first.getUserID(), second.getUserID());
		assertNotEquals(first.getStructureID(), second.getStructureID());
		assertEquals(Set.of(UserRole.ADMIN), first.getRoles());
		assertEquals(Set.of(UserRole.EMPLOYEE), second.getRoles());
	}

	@Test
	void protectsRolesFromExternalChanges() {

		Set<UserRole> roles = new HashSet<>();
		roles.add(UserRole.EMPLOYEE);

		StructureMembership membership = new StructureMembership(UUID.randomUUID(), UUID.randomUUID(), roles);
		roles.add(UserRole.ADMIN);

		assertEquals(Set.of(UserRole.EMPLOYEE), membership.getRoles());
		assertThrows(UnsupportedOperationException.class, () -> membership.getRoles().add(UserRole.ADMIN));
	}

	@Test
	void rejectsNullUserId() {
		assertThrows(NullPointerException.class,
				() -> new StructureMembership(null, UUID.randomUUID(), Set.of(UserRole.EMPLOYEE)));
	}

	@Test
	void rejectsNullStructureId() {
		assertThrows(NullPointerException.class,
				() -> new StructureMembership(UUID.randomUUID(), null, Set.of(UserRole.EMPLOYEE)));
	}

	@Test
	void rejectsNullRoles() {
		assertThrows(NullPointerException.class,
				() -> new StructureMembership(UUID.randomUUID(), UUID.randomUUID(), null));
	}
}
