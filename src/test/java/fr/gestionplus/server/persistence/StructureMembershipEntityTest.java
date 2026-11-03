
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;

class StructureMembershipEntityTest {

	@Test
	void preservesMembershipDuringConversion() {

		UUID userID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();

		StructureMembership membership = new StructureMembership(userID, structureID,
				Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));
		StructureMembershipEntity entity = new StructureMembershipEntity(membership);
		StructureMembership restored = entity.toDomain();

		assertEquals(userID, restored.getUserID());
		assertEquals(structureID, restored.getStructureID());
		assertEquals(membership.getRoles(), restored.getRoles());
	}

	@Test
	void rejectsNullMembership() {
		assertThrows(NullPointerException.class, () -> new StructureMembershipEntity(null));
	}
}
