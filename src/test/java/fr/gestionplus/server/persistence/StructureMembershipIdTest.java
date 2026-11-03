
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class StructureMembershipIdTest {

	@Test
	void sameUserAndStructureAreEqual() {

		UUID userID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();

		StructureMembershipId first = new StructureMembershipId(userID, structureID);
		StructureMembershipId second = new StructureMembershipId(userID, structureID);

		assertEquals(first, second);
		assertEquals(first.hashCode(), second.hashCode());
	}

	@Test
	void differentUserOrStructureAreNotEqual() {

		UUID userID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();

		StructureMembershipId original = new StructureMembershipId(userID, structureID);
		StructureMembershipId differentUser = new StructureMembershipId(UUID.randomUUID(), structureID);
		StructureMembershipId differentStructure = new StructureMembershipId(userID, UUID.randomUUID());

		assertNotEquals(original, differentUser);
		assertNotEquals(original, differentStructure);
		assertNotEquals(original, null);
	}

	@Test
	void hashSetRecognizesSameMembership() {

		UUID userID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();

		Set<StructureMembershipId> memberships = new HashSet<>();

		memberships.add(new StructureMembershipId(userID, structureID));
		memberships.add(new StructureMembershipId(userID, structureID));

		assertEquals(1, memberships.size());
	}
}
