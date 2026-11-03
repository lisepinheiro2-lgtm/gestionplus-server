
package fr.gestionplus.server.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganizationalStructureTest {

	@Test
	void preservesStructureIdentityAndName() {

		UUID structureID = UUID.randomUUID();
		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Association A");

		assertEquals(structureID, structure.getStructureID());
		assertEquals("Association A", structure.getStructureName());
	}

	@Test
	void removesUnnecessarySpacesFromName() {
		OrganizationalStructure structure = new OrganizationalStructure(UUID.randomUUID(), "   Association A   ");
		assertEquals("Association A", structure.getStructureName());
	}

	@Test
	void rejectsBlankStructureName() {
		assertThrows(IllegalArgumentException.class, () -> new OrganizationalStructure(UUID.randomUUID(), "   "));
	}

	@Test
	void rejectsNullStructureName() {
		assertThrows(NullPointerException.class, () -> new OrganizationalStructure(UUID.randomUUID(), null));
	}

	@Test
	void rejectsNullStructureId() {
		assertThrows(NullPointerException.class, () -> new OrganizationalStructure(null, "Association A"));
	}
}
