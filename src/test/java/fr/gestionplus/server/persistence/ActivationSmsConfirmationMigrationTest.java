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
	void v28RevokesOpenChallengesAndV36PreservesActivationHistory() {

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
					.locations("classpath:db/migration").target("28").load().migrate();
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

			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration").target("35").load().migrate();
			UUID currentChallengeID = UUID.randomUUID();
			jdbc.update("""
					INSERT INTO account_activation_identity_challenges
					    (challenge_id, user_id, invitation_id, phone_number, code_digest,
					     created_at, expires_at, send_confirmed_at, send_recovery_at)
					VALUES (?, ?, ?, '+12025550100', ?, CURRENT_TIMESTAMP,
					    CURRENT_TIMESTAMP + INTERVAL '5 minutes', CURRENT_TIMESTAMP,
					    CURRENT_TIMESTAMP + INTERVAL '2 minutes')
					""", currentChallengeID, userID, invitationID, "b".repeat(64));
			String currentChallenge = """
					SELECT user_id, invitation_id, code_digest, created_at, expires_at, send_confirmed_at,
					    send_recovery_at, consumed_at, revoked_at FROM %s WHERE challenge_id = ?
					""";
			var before = jdbc.queryForMap(currentChallenge.formatted("account_activation_identity_challenges"),
					currentChallengeID);

			Flyway.configure().dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
					.locations("classpath:db/migration").target("36").load().migrate();
			assertEquals(before, jdbc.queryForMap(currentChallenge.formatted("sms_identity_challenges"),
					currentChallengeID));
			assertEquals(4, jdbc.queryForObject("""
					SELECT COUNT(*) FROM sms_identity_challenges
					WHERE user_id = ? AND invitation_id = ? AND purpose = 'ACCOUNT_ACTIVATION'
					    AND session_id_hash IS NULL AND session_version IS NULL
					""", Integer.class, userID, invitationID));
		}

	}
}
