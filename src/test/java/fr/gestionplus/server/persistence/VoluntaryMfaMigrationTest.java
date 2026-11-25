package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

class VoluntaryMfaMigrationTest {

	@Test
	void preservesLegacyEnrollmentPurposesWithoutTrustingHistoricalPhones() {
		try (var postgres = new PostgreSQLContainer("postgres:16-alpine")
				.withDatabaseName("fictitious_voluntary_mfa_migration").withUsername("fictitious_migration")
				.withPassword(UUID.randomUUID().toString())
				.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
						new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))))) {
			postgres.start();
			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration").target("36").load().migrate();
			var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()));
			UUID initialUser = insertLegacyEnrollment(jdbc);
			UUID recoveryUser = insertLegacyEnrollment(jdbc);
			jdbc.update("INSERT INTO account_mfa_state (user_id, last_accepted_step) VALUES (?, -1)", recoveryUser);
			jdbc.update("""
					INSERT INTO sms_identity_challenges
					    (challenge_id, user_id, invitation_id, phone_number, purpose, code_digest, created_at,
					     expires_at, send_confirmed_at, send_recovery_at, consumed_at)
					VALUES (?, ?, ?, '+12025550100', 'ACCOUNT_ACTIVATION', ?,
					    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '5 minutes',
					    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + INTERVAL '2 minutes', CURRENT_TIMESTAMP)
					""", UUID.randomUUID(), initialUser, UUID.randomUUID(), "a".repeat(64));

			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration").target("38").load().migrate();
			assertEquals(2, jdbc.queryForObject(
					"SELECT COUNT(*) FROM user_accounts WHERE phone_verified_at IS NULL", Integer.class));
			assertEquals("INITIAL", purpose(jdbc, initialUser));
			assertEquals("RECOVERY", purpose(jdbc, recoveryUser));
			assertThrows(DataIntegrityViolationException.class,
					() -> jdbc.update("UPDATE account_mfa_enrollments SET purpose = 'VOLUNTARY' WHERE user_id = ?",
							initialUser));
		}
	}

	private static UUID insertLegacyEnrollment(JdbcTemplate jdbc) {
		UUID userID = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_accounts (user_id, user_name, status, user_first_name, user_last_name,
				    user_email, user_phone_number)
				VALUES (?, ?, 'ACTIVE', 'Fictitious', 'Migration', ?, '+12025550100')
				""", userID, "legacy_" + userID, userID + "@example.invalid");
		jdbc.update("""
				INSERT INTO account_mfa_enrollments (user_id, enrollment_id, encrypted_secret, session_version, expires_at)
				VALUES (?, ?, ?, 0, CURRENT_TIMESTAMP + INTERVAL '10 minutes')
				""", userID, UUID.randomUUID(), new byte[] {1});
		return userID;
	}

	private static String purpose(JdbcTemplate jdbc, UUID userID) {
		return jdbc.queryForObject("SELECT purpose FROM account_mfa_enrollments WHERE user_id = ?",
				String.class, userID);
	}
}
