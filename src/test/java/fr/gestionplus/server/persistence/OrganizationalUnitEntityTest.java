package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.organization.OrganizationalUnit;

class OrganizationalUnitEntityTest {

	@Test
	void preservesUnitDuringConversion() {
		UUID unitId = UUID.randomUUID();
		OrganizationalUnit unit = new OrganizationalUnit(unitId, "Secteur de test");

		OrganizationalUnit restored = new OrganizationalUnitEntity(unit).toDomain();

		assertEquals(unitId, restored.getUnitId());
		assertEquals(unit.getName(), restored.getName());
	}
}
