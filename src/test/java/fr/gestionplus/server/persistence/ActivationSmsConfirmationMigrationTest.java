package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

class ActivationSmsConfirmationMigrationTest {

	@Test
	void upgradingV27RevokesOpenChallengesWithoutDeletingAnyAttempt() {

		try (var postgres = new PostgreSQLContainer("postgres:16-alpine")
				.withDatabaseName("fictitious_sms_migration").withUsername("fictitious_migration")
				.withPassword(UUID.randomUUID().toString())
				.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
						new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))))) {
			postgres.start();
			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration").target("27").load().migrate();
			var jdbc = new JdbcTemplate(new DriverManagerDataSource(postgres.getJdbcUrl(), postgres.getUsername(),
					postgres.getPassword()));
			UUID employeeID = UUID.randomUUID();
			UUID userID = UUID.randomUUID();
			UUID invitationID = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO employees (employee_id, first_name, last_name, contract_hours) VALUES (?, 'Fictitious', 'Migration', 35)",
					employeeID);
			jdbc.update(
					"""
							INSERT INTO user_accounts (user_id, employee_id, status, user_first_name, user_last_name,
							    user_email, user_phone_number)
							VALUES (?, ?, 'PENDING_ACTIVATION', 'Fictitious', 'Migration', 'migration@example.invalid', '+12025550100')
							""",
					userID, employeeID);

			for (int index = 0; index < 3; index++) {
				jdbc.update(
						"""
								INSERT INTO account_activation_identity_challenges
								    (challenge_id, user_id, invitation_id, phone_number, code_digest, expires_at, consumed_at, revoked_at)
								VALUES (?, ?, ?, '+12025550100', ?, CURRENT_TIMESTAMP + INTERVAL '5 minutes',
								    CASE WHEN ? = 1 THEN CURRENT_TIMESTAMP END,
								    CASE WHEN ? = 2 THEN CURRENT_TIMESTAMP END)
								""",
						UUID.randomUUID(), userID, invitationID, "a".repeat(64), index, index);
			}

			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration").load().migrate();
			assertEquals(3,
					jdbc.queryForObject("SELECT COUNT(*) FROM account_activation_identity_challenges", Integer.class));
			assertEquals(0, jdbc.queryForObject(
					"SELECT COUNT(*) FROM account_activation_identity_challenges WHERE consumed_at IS NULL AND revoked_at IS NULL",
					Integer.class));
			assertEquals(1,
					jdbc.queryForObject(
							"SELECT COUNT(*) FROM account_activation_identity_challenges WHERE consumed_at IS NOT NULL",
							Integer.class));
			assertNotNull(
					jdbc.queryForObject("SELECT MIN(send_recovery_at) FROM account_activation_identity_challenges",
							java.time.OffsetDateTime.class));
		}

	}
}
