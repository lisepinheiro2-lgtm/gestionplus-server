
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
		jdbcTemplate.update("INSERT INTO user_accounts (user_id, user_name, status) VALUES (?, ?, ?)", userID,
				"test_" + userID, "ACTIVE");

		return userID;
	}

	private UUID createStructure(String name) {
		
		UUID structureID = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO organizational_structures (structure_id, structure_name) VALUES (?, ?)",
				structureID, name);

		return structureID;
	}

	private void addMembership(UUID userID, UUID structureID) {
		jdbcTemplate.update("INSERT INTO structure_memberships (user_id, structure_id) VALUES (?, ?)", userID,
				structureID);
	}

	@Test
	void appliesV8AndCreatesThreeTables() {

		Long applied = jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM flyway_schema_history WHERE version = '8' AND success = TRUE", Long.class);
		assertEquals(1L, applied);

		Long tables = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.tables
				WHERE table_schema = 'public'
				AND table_name IN (
				    'organizational_structures',
				    'structure_memberships',
				    'structure_membership_roles'
				)
				""", Long.class);
		assertEquals(3L, tables);
	}

	@Test
	void storesDifferentRolesForSameUserInTwoStructures() {

		UUID userID = createAccount();
		UUID structureA = createStructure("Association A");
		UUID structureB = createStructure("Association B");

		addMembership(userID, structureA);
		addMembership(userID, structureB);

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

		UUID structureID = createStructure("Association A");
		assertThrows(DataIntegrityViolationException.class, () -> addMembership(UUID.randomUUID(), structureID));

		UUID userID = createAccount();
		assertThrows(DataIntegrityViolationException.class, () -> addMembership(userID, UUID.randomUUID()));
	}

	@Test
	void rejectsRolesWithoutMembershipAndDuplicates() {

		UUID userID = createAccount();
		UUID structureID = createStructure("Association A");

		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate
				.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureID, "ADMIN"));

		addMembership(userID, structureID);
		assertThrows(DataIntegrityViolationException.class, () -> addMembership(userID, structureID));

		jdbcTemplate.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureID, "ADMIN");
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate
				.update("INSERT INTO structure_membership_roles VALUES (?, ?, ?)", userID, structureID, "ADMIN"));
	}

	@Test
	void rejectsBlankAndNullStructureNames() {
		assertThrows(DataIntegrityViolationException.class, () -> createStructure("   "));
		assertThrows(DataIntegrityViolationException.class, () -> createStructure(""));
		assertThrows(DataIntegrityViolationException.class, () -> createStructure(null));
	}
}
