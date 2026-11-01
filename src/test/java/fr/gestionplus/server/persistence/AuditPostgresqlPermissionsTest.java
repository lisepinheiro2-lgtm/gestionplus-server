package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = { "spring.jpa.hibernate.ddl-auto=validate", "spring.jpa.open-in-view=false",
		"spring.flyway.enabled=true" })
@Testcontainers
public class AuditPostgresqlPermissionsTest {

	@Container
	static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine")
			.withDatabaseName("gestionplus_audit_test").withUsername("test").withPassword("test")
			.withInitScript("db/init-audit-roles.sql");

	@DynamicPropertySource
	static void configureDatabase(DynamicPropertyRegistry registry) {

		registry.add("spring.datasource.url", postgres::getJdbcUrl);
		registry.add("spring.datasource.username", postgres::getUsername);
		registry.add("spring.datasource.password", postgres::getPassword);

		registry.add("spring.flyway.url", postgres::getJdbcUrl);
		registry.add("spring.flyway.user", () -> "audit_migration_test");
		registry.add("spring.flyway.password", () -> "migration_test_only");
	}

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private DataSource dataSource;

	@Test
	void usesIsolatedPostgresqlDatabase() {

		String database = jdbcTemplate.queryForObject("SELECT current_database()", String.class);

		String user = jdbcTemplate.queryForObject("SELECT current_user", String.class);

		assertEquals("gestionplus_audit_test", database);
		assertEquals("test", user);
	}

	@Test
	void createsRestrictedApplicationRole() throws SQLException {

		jdbcTemplate.execute("CREATE ROLE audit_app_test NOLOGIN");

		Boolean superuser = jdbcTemplate
				.queryForObject("SELECT rolsuper FROM pg_roles " + "WHERE rolname = 'audit_app_test'", Boolean.class);
		assertEquals(false, superuser);

		jdbcTemplate.execute("GRANT USAGE ON SCHEMA public TO audit_app_test");
		jdbcTemplate.execute(
				"GRANT SELECT, INSERT ON TABLE " + "public.audit_events, public.audit_targets " + "TO audit_app_test");

		Boolean canInsert = jdbcTemplate.queryForObject(
				"SELECT has_table_privilege(" + "'audit_app_test', 'public.audit_events', 'INSERT')", Boolean.class);
		assertEquals(true, canInsert);

		Boolean canUpdate = jdbcTemplate.queryForObject(
				"SELECT has_table_privilege(" + "'audit_app_test', 'public.audit_events', 'UPDATE')", Boolean.class);
		assertFalse(canUpdate);

		Boolean cantDeleteEvent = jdbcTemplate.queryForObject(
				"SELECT has_table_privilege(" + "'audit_app_test', 'public.audit_events', 'DELETE')", Boolean.class);
		assertFalse(cantDeleteEvent);

		Boolean cantUpdateTarget = jdbcTemplate.queryForObject(
				"SELECT has_table_privilege(" + "'audit_app_test', 'public.audit_targets', 'UPDATE')", Boolean.class);
		assertFalse(cantUpdateTarget);

		Boolean cantDeleteTarget = jdbcTemplate.queryForObject(
				"SELECT has_table_privilege(" + "'audit_app_test', 'public.audit_targets', 'DELETE')", Boolean.class);
		assertFalse(cantDeleteTarget);

		Boolean canTruncateEvent = jdbcTemplate.queryForObject(
				"SELECT has_table_privilege(" + "'audit_app_test', 'public.audit_events', 'TRUNCATE')", Boolean.class);
		assertFalse(canTruncateEvent);

		Boolean canTruncateTarget = jdbcTemplate.queryForObject(
				"SELECT has_table_privilege(" + "'audit_app_test', 'public.audit_targets', 'TRUNCATE')", Boolean.class);
		assertFalse(canTruncateTarget);

		String eventOwner = jdbcTemplate.queryForObject(
				"SELECT pg_get_userbyid(relowner) " + "FROM pg_class " + "WHERE oid = 'public.audit_events'::regclass",
				String.class);
		assertNotEquals("audit_app_test", eventOwner);

		String targetOwner = jdbcTemplate.queryForObject(
				"SELECT pg_get_userbyid(relowner) " + "FROM pg_class " + "WHERE oid = 'public.audit_targets'::regclass",
				String.class);
		assertNotEquals("audit_app_test", targetOwner);

		Boolean canCreateTable = jdbcTemplate.queryForObject(
				"SELECT has_schema_privilege(" + "'audit_app_test', 'public', 'CREATE')", Boolean.class);
		assertFalse(canCreateTable);

		Boolean canBecomeOwner = jdbcTemplate.queryForObject("SELECT pg_has_role(?, ?, 'SET')", Boolean.class,
				"audit_app_test", eventOwner);
		assertFalse(canBecomeOwner);

		Boolean canInheritOwnerRights = jdbcTemplate.queryForObject("SELECT pg_has_role(?, ?, 'USAGE')", Boolean.class,
				"audit_app_test", eventOwner);
		assertFalse(canInheritOwnerRights);

		Boolean canBecomeTargetOwner = jdbcTemplate.queryForObject("SELECT pg_has_role(?, ?, 'SET')", Boolean.class,
				"audit_app_test", targetOwner);
		assertFalse(canBecomeTargetOwner);

		Boolean canInheritTargetOwnerRights = jdbcTemplate.queryForObject("SELECT pg_has_role(?, ?, 'USAGE')",
				Boolean.class, "audit_app_test", targetOwner);
		assertFalse(canInheritTargetOwnerRights);

		Boolean migrationRoleExists = jdbcTemplate.queryForObject(
				"SELECT EXISTS (SELECT 1 FROM pg_roles " + "WHERE rolname = 'audit_migration_test')", Boolean.class);
		assertEquals(true, migrationRoleExists);

		assertEquals("audit_migration_test", eventOwner);
		assertEquals("audit_migration_test", targetOwner);

		String flywayOwner = jdbcTemplate.queryForObject("SELECT pg_get_userbyid(relowner) " + "FROM pg_class "
				+ "WHERE oid = 'public.flyway_schema_history'::regclass", String.class);
		assertEquals("audit_migration_test", flywayOwner);

		jdbcTemplate.execute("GRANT SELECT, INSERT ON TABLE " + "public.audit_events, public.audit_targets "
				+ "TO audit_application_test");

		try (Connection appConnection = DriverManager.getConnection(postgres.getJdbcUrl(), "audit_application_test",
				"application_test_only"); Statement appStatement = appConnection.createStatement()) {

			try (ResultSet result = appStatement.executeQuery("SELECT current_user")) {
				assertTrue(result.next());
				assertEquals("audit_application_test", result.getString(1));
			}

			try (ResultSet result = appStatement.executeQuery("SELECT COUNT(*) FROM public.audit_events")) {
				assertTrue(result.next());
			}

			UUID appEventId = UUID.randomUUID();

			try (PreparedStatement request = appConnection.prepareStatement("INSERT INTO public.audit_events "
					+ "(event_id, occurred_at, action, result) " + "VALUES (?, CURRENT_TIMESTAMP, ?, ?)")) {
				request.setObject(1, appEventId);
				request.setString(2, "AUTH_LOGIN");
				request.setString(3, "FAILURE");

				int rows = request.executeUpdate();
				assertEquals(1, rows);
			}

			SQLException updateDenied = assertThrows(SQLException.class,
					() -> appStatement.execute("UPDATE public.audit_events SET result = result WHERE FALSE"));
			assertEquals("42501", updateDenied.getSQLState());

			SQLException deleteDenied = assertThrows(SQLException.class,
					() -> appStatement.execute("DELETE FROM public.audit_events WHERE FALSE"));
			assertEquals("42501", deleteDenied.getSQLState());
		}

		try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {

			statement.execute("SET ROLE audit_app_test");

			try {

				try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM public.audit_events")) {
					assertTrue(result.next());
				}

				try (ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM public.audit_targets")) {
					assertTrue(result.next());
				}

				UUID eventId = UUID.randomUUID();
				UUID targetId = UUID.randomUUID();

				try (PreparedStatement request = connection.prepareStatement("INSERT INTO public.audit_events "
						+ "(event_id, occurred_at, action, result) " + "VALUES (?, CURRENT_TIMESTAMP, ?, ?)")) {
					request.setObject(1, eventId);
					request.setString(2, "AUTH_LOGIN");
					request.setString(3, "FAILURE");

					int rows = request.executeUpdate();
					assertEquals(1, rows);
				}

				try (PreparedStatement request = connection.prepareStatement("INSERT INTO public.audit_targets\r\n"
						+ "    (event_id, target_type, target_id)\r\n" + "VALUES (?, ?, ?)")) {
					request.setObject(1, eventId);
					request.setString(2, "EMPLOYEE");
					request.setObject(3, targetId);

					int rows = request.executeUpdate();
					assertEquals(1, rows);
				}

				try (PreparedStatement request = connection.prepareStatement("SELECT COUNT(*)\r\n"
						+ "FROM public.audit_targets\r\n" + "WHERE event_id = ?\r\n" + "AND target_id = ?")) {
					request.setObject(1, eventId);
					request.setObject(2, targetId);

					try (ResultSet result = request.executeQuery()) {
						assertTrue(result.next());
						assertEquals(1, result.getInt(1));
					}
				}

				SQLException exception = assertThrows(SQLException.class,
						() -> statement.execute("DELETE FROM public.audit_events WHERE FALSE"));
				assertEquals("42501", exception.getSQLState());

				SQLException updateException = assertThrows(SQLException.class,
						() -> statement.execute("UPDATE public.audit_events SET result = result WHERE FALSE"));
				assertEquals("42501", updateException.getSQLState());

				SQLException forbidenDelete = assertThrows(SQLException.class,
						() -> statement.execute("DELETE FROM public.audit_targets WHERE FALSE"));
				assertEquals("42501", forbidenDelete.getSQLState());

				SQLException forbidenUpdate = assertThrows(SQLException.class,
						() -> statement.execute("UPDATE public.audit_targets SET target_id = target_id WHERE FALSE"));
				assertEquals("42501", forbidenUpdate.getSQLState());

			} finally {
				statement.execute("RESET ROLE");
			}
		}
	}
}