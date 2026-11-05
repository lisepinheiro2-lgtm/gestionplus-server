package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.organization.TenantOrganization;

class TenantOrganizationEntityTest {

	@Test
	void preservesTenantIdentityNameAndSuperAdminDuringConversion() {
		UUID tenantID = UUID.randomUUID();
		UUID superAdminUserID = UUID.randomUUID();
		TenantOrganization tenant = new TenantOrganization(tenantID, "   Entreprise A   ", superAdminUserID);

		TenantOrganization restored = new TenantOrganizationEntity(tenant).toDomain();

		assertEquals(tenantID, restored.getTenantID());
		assertEquals("Entreprise A", restored.getTenantName());
		assertEquals(superAdminUserID, restored.getSuperAdminUserID());
	}

	@Test
	void rejectsNullTenant() {
		assertThrows(NullPointerException.class, () -> new TenantOrganizationEntity(null));
	}
}
