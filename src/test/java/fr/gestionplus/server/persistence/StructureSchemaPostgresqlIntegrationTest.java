
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_structure_v8_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true" })
class StructureSchemaPostgresqlIntegrationTest {

	@Autowired
	private JdbcTemplate jdbcTemplate;

	private UUID createAccount() {

		UUID userID = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO user_accounts (
				    user_id, user_name, status, user_last_name, user_first_name, user_email, user_phone_number
				)
				VALUES (?, ?, ?, ?, ?, ?, ?)
				""", userID, "test_" + userID, "ACTIVE", "Exemple", "Test", "test@example.invalid", "0600000000");

		return userID;
	}

	private UUID createTenant() {
		UUID superAdminUserID = createAccount();
		jdbcTemplate.update("INSERT INTO user_account_roles (user_id, role) VALUES (?, ?)",
				superAdminUserID, "SUPER_ADMIN");
		return createTenant("Entreprise", superAdminUserID);
	}

	private UUID createTenant(String name, UUID superAdminUserID) {
		UUID tenantID = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, ?, ?)",
				tenantID, name, superAdminUserID);
		return tenantID;
	}

	private UUID createStructure(String name, UUID tenantID) {
		UUID structureID = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO organizational_structures (structure_id, structure_name, tenant_id) VALUES (?, ?, ?)",
				structureID, name, tenantID);
		return structureID;
	}

	private void assertSqlState(String sqlState, Runnable operation) {
		DataIntegrityViolationException exception = assertThrows(DataIntegrityViolationException.class, operation::run);
		SQLException cause = assertInstanceOf(SQLException.class, exception.getMostSpecificCause());
		assertEquals(sqlState, cause.getSQLState());
	}

	private void joinTenant(UUID userID, UUID tenantID) {
		jdbcTemplate.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);
	}

	private void addMembership(UUID userID, UUID structureID, UUID tenantID) {
		jdbcTemplate.update("INSERT INTO structure_memberships (user_id, structure_id, tenant_id) VALUES (?, ?, ?)",
				userID, structureID, tenantID);
	}

	@Test
	void appliesV8AndV9AndCreatesStructureAndTenantTables() {

		Long applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version IN ('8', '9') AND success = TRUE",
				Long.class);
		assertEquals(2L, applied);

		Long tables = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.tables
				WHERE table_schema = 'public'
				AND table_name IN (
				    'organizational_structures',
				    'structure_memberships',
				    'structure_membership_roles',
				    'tenant_organizations'
				)
				""", Long.class);
		assertEquals(4L, tables);
	}

	@Test
	void storesDifferentRolesForSameUserInTwoStructures() {

		UUID userID = createAccount();
		UUID tenantID = createTenant();
		UUID structureA = createStructure("Association A", tenantID);
		UUID structureB = createStructure("Association B", tenantID);

		joinTenant(userID, tenantID);
		addMembership(userID, structureA, tenantID);
		addMembership(userID, structureB, tenantID);

		jdbcTemplate.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureA, "ADMIN");
		jdbcTemplate.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureA,
				"ACCOUNTANT");
		jdbcTemplate.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureB, "EMPLOYEE");

		Long rolesA = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM structure_membership_roles WHERE user_id = ? AND structure_id = ?", Long.class,
				userID, structureA);
		Long rolesB = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM structure_membership_roles WHERE user_id = ? AND structure_id = ?", Long.class,
				userID, structureB);

		assertEquals(2L, rolesA);
		assertEquals(1L, rolesB);
	}

	@Test
	void rejectsMembershipWithUnknownAccountOrStructure() {

		UUID tenantID = createTenant();
		UUID structureID = createStructure("Association A", tenantID);
		assertSqlState("23503", () -> addMembership(UUID.randomUUID(), structureID, tenantID));

		UUID userID = createAccount();
		joinTenant(userID, tenantID);
		assertSqlState("23503", () -> addMembership(userID, UUID.randomUUID(), tenantID));
	}

	@Test
	void rejectsStructureMembershipWhenTheAccountAndStructureTenantsDiffer() {
		UUID userID = createAccount();
		UUID accountTenant = createTenant();
		UUID structureTenant = createTenant();
		UUID structureID = createStructure("Autre entreprise", structureTenant);
		joinTenant(userID, accountTenant);

		assertSqlState("23503", () -> addMembership(userID, structureID, structureTenant));
		assertSqlState("23503", () -> addMembership(userID, structureID, accountTenant));
	}

	@Test
	void rejectsRolesWithoutMembershipAndDuplicates() {

		UUID userID = createAccount();
		UUID tenantID = createTenant();
		UUID structureID = createStructure("Association A", tenantID);
		joinTenant(userID, tenantID);

		assertSqlState("23503", () -> jdbcTemplate
				.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureID, "ADMIN"));

		addMembership(userID, structureID, tenantID);
		assertSqlState("23505", () -> addMembership(userID, structureID, tenantID));

		jdbcTemplate.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureID, "ADMIN");
		assertSqlState("23505", () -> jdbcTemplate
				.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureID, "ADMIN"));
	}

	@Test
	void rejectsBlankAndNullStructureNames() {
		UUID tenantID = createTenant();
		assertSqlState("23514", () -> createStructure("   ", tenantID));
		assertSqlState("23514", () -> createStructure("", tenantID));
		assertSqlState("23502", () -> createStructure(null, tenantID));
	}

	@Test
	void rejectsStructureWithoutTenant() {
		assertSqlState("23502", () -> createStructure("Association A", null));
	}

	@Test
	void rejectsStructureWithUnknownTenant() {
		assertSqlState("23503", () -> createStructure("Association A", UUID.randomUUID()));
	}

	@Test
	void rejectsBlankAndNullTenantNames() {
		UUID superAdminUserID = createAccount();

		assertSqlState("23514", () -> createTenant("   ", superAdminUserID));
		assertSqlState("23514", () -> createTenant("", superAdminUserID));
		assertSqlState("23502", () -> createTenant(null, superAdminUserID));
	}

	@Test
	void rejectsTenantWithoutSuperAdmin() {
		assertSqlState("23502", () -> createTenant("Entreprise", null));
	}

	@Test
	void rejectsTenantWithUnknownSuperAdmin() {
		assertSqlState("23503", () -> createTenant("Entreprise", UUID.randomUUID()));
	}

	@Test
	void rejectsSameSuperAdminForTwoTenants() {
		UUID superAdminUserID = createAccount();
		jdbcTemplate.update("INSERT INTO user_account_roles (user_id, role) VALUES (?, ?)",
				superAdminUserID, "SUPER_ADMIN");
		createTenant("Entreprise A", superAdminUserID);

		assertSqlState("23505", () -> createTenant("Entreprise B", superAdminUserID));
	}

	@Test
	void preservesTenantLinkForMultipleStructures() {
		UUID tenantID = createTenant();
		UUID structureA = createStructure("Association A", tenantID);
		UUID structureB = createStructure("Association B", tenantID);

		assertEquals(tenantID, jdbcTemplate.queryForObject(
				"SELECT tenant_id FROM organizational_structures WHERE structure_id = ?", UUID.class, structureA));
		assertEquals(tenantID, jdbcTemplate.queryForObject(
				"SELECT tenant_id FROM organizational_structures WHERE structure_id = ?", UUID.class, structureB));
	}

	@Test
	void rejectsDeletingTenantWhileStructuresReferenceIt() {
		UUID tenantID = createTenant();
		createStructure("Association A", tenantID);

		assertSqlState("23503", () -> jdbcTemplate.update(
				"DELETE FROM tenant_organizations WHERE tenant_id = ?", tenantID));
	}

	@Test
	void rejectsDeletingSuperAdminWhileTenantReferencesIt() {
		UUID tenantID = createTenant();
		UUID superAdminUserID = jdbcTemplate.queryForObject(
				"SELECT super_admin_user_id FROM tenant_organizations WHERE tenant_id = ?", UUID.class, tenantID);
		jdbcTemplate.update("DELETE FROM user_account_roles WHERE user_id = ?", superAdminUserID);

		assertSqlState("23503", () -> jdbcTemplate.update(
				"DELETE FROM user_accounts WHERE user_id = ?", superAdminUserID));
	}
}
