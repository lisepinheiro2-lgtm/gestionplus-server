package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentProof;

@Testcontainers
class MfaEnrollmentProofPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-22T12:00:00Z");
	private static final String HASH = "a".repeat(64);
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
			.withDatabaseName("fictitious_mfa_enrollment_proof").withUsername("fictitious_migration")
			.withPassword(UUID.randomUUID().toString())
			.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
					new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));
	private static JdbcTemplate jdbc;
	private static PostgresqlAccountMfaStore store;
	private static TransactionTemplate transactions;
	private static UUID existingAccount;

	@BeforeAll
	static void migrateAnExistingV28Database() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure().dataSource(source).locations("classpath:db/migration").target("28").load().migrate();
		jdbc = new JdbcTemplate(source);
		existingAccount = account();
		Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
		store = new PostgresqlAccountMfaStore(jdbc);
		transactions = new TransactionTemplate(new DataSourceTransactionManager(source));
	}

	@Test
	void upgradingV28PreservesExistingAccountsAndCreatesTheProofTable() {
		assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?",
				String.class, existingAccount));
		store.saveEnrollmentProof(new EnrollmentProof(existingAccount, HASH, 0, NOW.plusSeconds(300)));
		assertTrue(store.consumeEnrollmentProof(existingAccount, 0, HASH, NOW));
	}

	@Test
	void replacingAProofInvalidatesThePreviousProofAndTheReplacementIsConsumedOnlyOnce() {
		UUID userID = account();
		store.saveEnrollmentProof(new EnrollmentProof(userID, HASH, 7, NOW.plusSeconds(300)));
		String replacementHash = "b".repeat(64);
		store.saveEnrollmentProof(new EnrollmentProof(userID, replacementHash, 8, NOW.plusSeconds(600)));

		assertEquals(1, count(userID));
		assertFalse(store.consumeEnrollmentProof(userID, 7, HASH, NOW));
		assertFalse(store.consumeEnrollmentProof(userID, 7, replacementHash, NOW));
		assertTrue(store.consumeEnrollmentProof(userID, 8, replacementHash, NOW));
		assertFalse(store.consumeEnrollmentProof(userID, 8, replacementHash, NOW));
	}

	@Test
	void aWrongAccountVersionOrHashDoesNotConsumeTheProof() {
		UUID userID = account();
		UUID otherUserID = account();
		store.saveEnrollmentProof(new EnrollmentProof(userID, HASH, 7, NOW.plusSeconds(300)));

		assertFalse(store.consumeEnrollmentProof(otherUserID, 7, HASH, NOW));
		assertFalse(store.consumeEnrollmentProof(userID, 8, HASH, NOW));
		assertFalse(store.consumeEnrollmentProof(userID, 7, "b".repeat(64), NOW));
		assertTrue(store.consumeEnrollmentProof(userID, 7, HASH, NOW));
	}

	@Test
	void aProofIsRejectedAtAndAfterItsExpiry() {
		for (Instant attemptedAt : new Instant[] { NOW.plusSeconds(300), NOW.plusSeconds(301) }) {
			UUID userID = account();
			store.saveEnrollmentProof(new EnrollmentProof(userID, HASH, 7, NOW.plusSeconds(300)));
			assertFalse(store.consumeEnrollmentProof(userID, 7, HASH, attemptedAt));
			assertEquals(1, count(userID));
		}
	}

	@Test
	void concurrentConsumersCannotBothUseTheSameProof() throws Exception {
		UUID userID = account();
		store.saveEnrollmentProof(new EnrollmentProof(userID, HASH, 7, NOW.plusSeconds(300)));
		CyclicBarrier start = new CyclicBarrier(2);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return store.consumeEnrollmentProof(userID, 7, HASH, NOW);
			});
			var second = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return store.consumeEnrollmentProof(userID, 7, HASH, NOW);
			});
			assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
		}
		assertEquals(0, count(userID));
	}

	@Test
	void rollingBackTheOuterTransactionAlsoRollsBackProofCreation() {
		UUID userID = account();
		assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
			store.saveEnrollmentProof(new EnrollmentProof(userID, HASH, 7, NOW.plusSeconds(300)));
			throw new IllegalStateException("Activation fictive annulée.");
		}));
		assertEquals(0, count(userID));
	}

	@Test
	void rollingBackEnrollmentRestoresTheProofForAnotherAttempt() {
		UUID userID = account();
		store.saveEnrollmentProof(new EnrollmentProof(userID, HASH, 7, NOW.plusSeconds(300)));
		assertThrows(IllegalStateException.class, () -> transactions.executeWithoutResult(status -> {
			assertTrue(store.consumeEnrollmentProof(userID, 7, HASH, NOW));
			throw new IllegalStateException("Enrôlement fictif annulé.");
		}));
		assertTrue(store.consumeEnrollmentProof(userID, 7, HASH, NOW));
	}

	@Test
	void theSchemaRejectsUnknownAccountsInvalidDigestsAndNegativeVersions() {
		assertThrows(DataIntegrityViolationException.class, () -> store.saveEnrollmentProof(
				new EnrollmentProof(UUID.randomUUID(), HASH, 7, NOW.plusSeconds(300))));
		UUID userID = account();
		for (String invalidHash : new String[] { "a".repeat(63), "g".repeat(64), "A".repeat(64) }) {
			assertThrows(DataIntegrityViolationException.class, () -> store.saveEnrollmentProof(
					new EnrollmentProof(userID, invalidHash, 7, NOW.plusSeconds(300))));
		}
		assertThrows(DataIntegrityViolationException.class, () -> store.saveEnrollmentProof(
				new EnrollmentProof(userID, HASH, -1, NOW.plusSeconds(300))));
	}

	private static UUID account() {
		UUID userID = UUID.randomUUID();
		jdbc.update("""
				INSERT INTO user_accounts (user_id, user_name, status, user_first_name, user_last_name,
				    user_email, user_phone_number)
				VALUES (?, ?, 'ACTIVE', 'Fictitious', 'Enrollment', ?, '+12025550100')
				""", userID, "fictitious_" + userID, userID + "@example.invalid");
		return userID;
	}

	private int count(UUID userID) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM account_mfa_enrollment_proofs WHERE user_id = ?",
				Integer.class, userID);
	}
}
