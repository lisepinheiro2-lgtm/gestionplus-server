
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.organization.OrganizationalStructure;

class OrganizationalStructureEntityTest {

	@Test
	void preservesStructureDuringConversion() {

		UUID structureID = UUID.randomUUID();
		UUID tenantID = UUID.randomUUID();

		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Association A", tenantID);
		OrganizationalStructureEntity entity = new OrganizationalStructureEntity(structure);
		OrganizationalStructure restored = entity.toDomain();

		assertEquals(structureID, restored.getStructureID());
		assertEquals(tenantID, restored.getTenantID());
		assertEquals("Association A", restored.getStructureName());
	}

	@Test
	void preservesNormalizedStructureName() {

		OrganizationalStructure structure = new OrganizationalStructure(UUID.randomUUID(), "   Association B   ",
				UUID.randomUUID());
		OrganizationalStructureEntity entity = new OrganizationalStructureEntity(structure);
		OrganizationalStructure restored = entity.toDomain();

		assertEquals("Association B", restored.getStructureName());
		assertEquals(structure.getStructureID(), restored.getStructureID());
		assertEquals(structure.getTenantID(), restored.getTenantID());
	}

	@Test
	void rejectsNullStructure() {
		assertThrows(NullPointerException.class, () -> new OrganizationalStructureEntity(null));
	}
}
