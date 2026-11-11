package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class StructureMembershipMigrationTest {

	@Test
	void validatesTheHistoricalConstraintsWithoutChangingLegitimateMemberships() throws SQLException {
		DriverManagerDataSource dataSource = database();

		try (Connection retained = dataSource.getConnection()) {
			migrate(dataSource, "16");
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			Fixture fixture = prepare(jdbc, true);
			migrate(dataSource, "23");
			assertFalse(validated(jdbc));

			migrate(dataSource, "24");

			assertTrue(validated(jdbc));
			assertEquals(2, jdbc.queryForObject("""
					SELECT COUNT(*) FROM pg_constraint
					WHERE conname IN ('fk_membership_structure_tenant', 'fk_membership_user_tenant')
					AND convalidated
					""", Integer.class));
			assertEquals(1, membershipCount(jdbc, fixture));
			UUID outsider = account(jdbc);
			assertThrows(DataIntegrityViolationException.class, () -> jdbc.update("""
					INSERT INTO structure_memberships (user_id, structure_id, tenant_id) VALUES (?, ?, ?)
					""", outsider, fixture.structureID(), fixture.tenantID()));
		}

	}

	@Test
	void rejectsInconsistentHistoryWithoutInventingTenantRightsOrDeletingData() throws SQLException {
		DriverManagerDataSource dataSource = database();

		try (Connection retained = dataSource.getConnection()) {
			migrate(dataSource, "16");
			JdbcTemplate jdbc = new JdbcTemplate(dataSource);
			Fixture fixture = prepare(jdbc, false);
			migrate(dataSource, "23");

			assertThrows(FlywayException.class, () -> migrate(dataSource, "24"));

			assertFalse(validated(jdbc));
			assertEquals(1, membershipCount(jdbc, fixture));
			assertEquals(0, jdbc.queryForObject("""
					SELECT COUNT(*) FROM tenant_memberships WHERE user_id = ? AND tenant_id = ?
					""", Integer.class, fixture.userID(), fixture.tenantID()));
			jdbc.update("DELETE FROM structure_memberships WHERE user_id = ?", fixture.userID());
			migrate(dataSource, "24");
			assertTrue(validated(jdbc));
		}

	}

	private DriverManagerDataSource database() {
		DriverManagerDataSource dataSource = new DriverManagerDataSource();
		dataSource.setDriverClassName("org.testcontainers.jdbc.ContainerDatabaseDriver");
		dataSource.setUrl("jdbc:tc:postgresql:16-alpine:///membership_migration_" + UUID.randomUUID());
		dataSource.setUsername("test");
		dataSource.setPassword("test");
		return dataSource;
	}

	private void migrate(DriverManagerDataSource dataSource, String version) {
		Flyway.configure().dataSource(dataSource).target(version).load().migrate();
	}

	private Fixture prepare(JdbcTemplate jdbc, boolean legitimate) {
		UUID holder = account(jdbc);
		UUID userID = account(jdbc);
		UUID tenantID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, ?, ?)
				""", tenantID, "Entreprise test", holder);
		jdbc.update("""
				INSERT INTO organizational_structures (structure_id, structure_name, tenant_id) VALUES (?, ?, ?)
				""", structureID, "Structure test", tenantID);

		if (legitimate) {
			jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);
		}

		jdbc.update("INSERT INTO structure_memberships (user_id, structure_id) VALUES (?, ?)", userID, structureID);
		return new Fixture(userID, structureID, tenantID);
	}

	private UUID account(JdbcTemplate jdbc) {
		UUID userID = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_accounts
				    (user_id, status, user_last_name, user_first_name, user_email, user_phone_number)
				VALUES (?, 'PENDING_ACTIVATION', 'Exemple', 'Test', 'test@example.invalid', '0600000000')
				""", userID);
		return userID;
	}

	private boolean validated(JdbcTemplate jdbc) {
		return Boolean.TRUE.equals(jdbc.queryForObject("""
				SELECT convalidated FROM pg_constraint WHERE conname = 'fk_membership_user_tenant'
				""", Boolean.class));
	}

	private int membershipCount(JdbcTemplate jdbc, Fixture fixture) {
		return jdbc.queryForObject("""
				SELECT COUNT(*) FROM structure_memberships WHERE user_id = ? AND structure_id = ?
				""", Integer.class, fixture.userID(), fixture.structureID());
	}

	private record Fixture(UUID userID, UUID structureID, UUID tenantID) {
	}
}
