
package fr.gestionplus.server.organization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrganizationalStructureTest {

	@Test
	void preservesStructureIdentityAndName() {

		UUID structureID = UUID.randomUUID();
		UUID tenantID = UUID.randomUUID();
		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Association A", tenantID);

		assertEquals(structureID, structure.getStructureID());
		assertEquals(tenantID, structure.getTenantID());
		assertEquals("Association A", structure.getStructureName());
	}

	@Test
	void removesUnnecessarySpacesFromName() {
		OrganizationalStructure structure = new OrganizationalStructure(UUID.randomUUID(), "   Association A   ",
				UUID.randomUUID());
		assertEquals("Association A", structure.getStructureName());
	}

	@Test
	void rejectsBlankStructureName() {
		assertThrows(IllegalArgumentException.class,
				() -> new OrganizationalStructure(UUID.randomUUID(), "   ", UUID.randomUUID()));
	}

	@Test
	void rejectsNullStructureName() {
		assertThrows(NullPointerException.class,
				() -> new OrganizationalStructure(UUID.randomUUID(), null, UUID.randomUUID()));
	}

	@Test
	void rejectsNullStructureId() {
		assertThrows(NullPointerException.class,
				() -> new OrganizationalStructure(null, "Association A", UUID.randomUUID()));
	}

	@Test
	void rejectsNullTenantId() {
		assertThrows(NullPointerException.class,
				() -> new OrganizationalStructure(UUID.randomUUID(), "Association A", null));
	}
}
