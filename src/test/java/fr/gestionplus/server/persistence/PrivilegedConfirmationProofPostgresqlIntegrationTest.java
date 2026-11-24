package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import fr.gestionplus.server.account.PrivilegedConfirmationProofStore;
import fr.gestionplus.server.account.PrivilegedConfirmationProofStore.Proof;
import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;

@Testcontainers
class PrivilegedConfirmationProofPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
	private static final long VERSION = 7;
	private static final String SESSION = "9".repeat(64);
	private static final String PREVIEW = "a".repeat(64);
	private static final String HASH = "b".repeat(64);
	private static final String OTHER_HASH = "c".repeat(64);
	private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
	private static final Clock CLOCK = new Clock() {
		@Override
		public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override
		public Clock withZone(ZoneId zone) { return this; }
		@Override
		public Instant instant() { return TIME.get(); }
	};
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
			.withDatabaseName("fictitious_privileged_confirmations").withUsername("fictitious_migration")
			.withPassword(UUID.randomUUID().toString())
			.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
					new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));
	private static JdbcTemplate jdbcTemplate;
	private static TransactionTemplate transaction;
	private static PrivilegedConfirmationProofStore proofStore;
	private static UUID existingAccount;
	private static UUID existingNotification;

	@BeforeAll
	static void upgradeAnExistingV31Database() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure().dataSource(source).locations("classpath:db/migration").target("31").load().migrate();
		jdbcTemplate = new JdbcTemplate(source);
		existingAccount = account();
		existingNotification = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO account_deactivation_notifications
				    (notification_id, actor_user_id, target_id, recipient_user_id, kind)
				VALUES (?, ?, ?, ?, 'MFA_ENROLLED')
				""", existingNotification, existingAccount, existingAccount, existingAccount);
		Flyway.configure().dataSource(source).locations("classpath:db/migration").target("33").load().migrate();
		jdbcTemplate.update("""
				INSERT INTO privileged_confirmation_proofs
				    (user_id, action, session_version, preview_digest, proof_hash, created_at, expires_at)
				VALUES (?, 'SUPER_ADMIN_SUCCESSION_OFFER', ?, ?, ?, ?, ?)
				""", existingAccount, VERSION, PREVIEW, HASH, NOW.atOffset(ZoneOffset.UTC),
				NOW.plusSeconds(300).atOffset(ZoneOffset.UTC));
		Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
		assertEquals(0, count(existingAccount));
		var transactionManager = new DataSourceTransactionManager(source);
		transaction = new TransactionTemplate(transactionManager);
		ProxyFactory proxy = new ProxyFactory(new PostgresqlPrivilegedConfirmationProofStore(jdbcTemplate, CLOCK));
		proxy.addAdvice(new TransactionInterceptor(transactionManager, new AnnotationTransactionAttributeSource()));
		proofStore = (PrivilegedConfirmationProofStore) proxy.getProxy();
	}

	@BeforeEach
	void clearOnlyTheIsolatedProofsAndResetTheClock() {
		TIME.set(NOW);
		jdbcTemplate.update("DELETE FROM privileged_confirmation_proofs");
	}

	@Test
	void upgradingV31PreservesExistingAccountsAndPendingNotifications() {
		assertEquals("ACTIVE", jdbcTemplate.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?", String.class, existingAccount));
		assertEquals(existingAccount, jdbcTemplate.queryForObject("SELECT recipient_user_id FROM account_deactivation_notifications WHERE notification_id = ?", UUID.class, existingNotification));
		replace(existingAccount, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		assertTrue(consume(existingAccount, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
	}

	@Test
	void replacementInvalidatesThePreviousProofOnlyForTheSameUserAndAction() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH);
		assertEquals(1, count(userID));
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
		assertTrue(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH, NOW));
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH, NOW));
	}

	@Test
	void creationAndAdminTransferRemainIndependentEvenForTheSameUserAndPreview() {
		UUID userID = account();
		replace(userID, Action.SUPER_ADMIN_SUCCESSION_CREATE, HASH);
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH);
		replace(userID, Action.SUPER_ADMIN_SUCCESSION_CREATE, "d".repeat(64));
		assertEquals(2, count(userID));
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, "d".repeat(64), NOW));
		assertFalse(consume(userID, Action.SUPER_ADMIN_SUCCESSION_CREATE, HASH, NOW));
		assertTrue(consume(userID, Action.SUPER_ADMIN_SUCCESSION_CREATE, "d".repeat(64), NOW));
		assertEquals(1, count(userID));
		assertTrue(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH, NOW));
	}

	@Test
	void anotherUserCannotConsumeOrReplaceTheOwnersProof() {
		UUID first = account(), second = account();
		replace(first, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		replace(second, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH);
		assertFalse(consume(second, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
		assertTrue(consume(first, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
		assertTrue(consume(second, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH, NOW));
	}

	@ParameterizedTest
	@ValueSource(ints = { 299, 300, 301 })
	void expiryIsStrictAtFiveMinutes(int seconds) {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		assertEquals(seconds < 300, consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW.plusSeconds(seconds)));
	}

	@Test
	void aStaleTimestampCannotBypassTheCurrentClock() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		TIME.set(NOW.plusSeconds(300));
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
	}

	@Test
	void aChangedVersionOrPreviewOrActionIsRefusedWithoutConsumingTheValidProof() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		boolean versionAccepted = transaction.execute(status -> proofStore.consumeIfValid(userID, SESSION, VERSION + 1,
				Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, HASH, NOW));
		boolean previewAccepted = transaction.execute(status -> proofStore.consumeIfValid(userID, SESSION, VERSION,
				Action.STRUCTURE_ADMIN_TRANSFER, "f".repeat(64), HASH, NOW));
		assertFalse(versionAccepted);
		assertFalse(previewAccepted);
		assertFalse(consume(userID, Action.SUPER_ADMIN_SUCCESSION_CREATE, HASH, NOW));
		assertTrue(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
	}

	@Test
	void aSecurityVersionChangedInTheDatabaseInvalidatesEvenOtherwiseMatchingArguments() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		jdbcTemplate.update("UPDATE user_accounts SET session_version = session_version + 1 WHERE user_id = ?", userID);
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
		assertEquals(1, count(userID));
	}

	@Test
	void aDisabledAccountCannotIssueOrConsumeAProof() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		jdbcTemplate.update("UPDATE user_accounts SET status = 'DISABLED' WHERE user_id = ?", userID);
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
		assertThrows(IllegalArgumentException.class, () -> replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH));
	}

	@Test
	void rawTokensAndNonSha256DigestsAreNeverPersisted() {
		UUID userID = account();
		assertThrows(IllegalArgumentException.class, () -> replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, "r".repeat(43)));
		assertEquals(0, count(userID));
		assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(status -> proofStore.replaceCurrent(
				new Proof(userID, SESSION, VERSION, Action.STRUCTURE_ADMIN_TRANSFER, "not-a-digest", HASH, NOW.plusSeconds(300)))));
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		assertEquals(HASH, jdbcTemplate.queryForObject("SELECT proof_hash FROM privileged_confirmation_proofs WHERE user_id = ?", String.class, userID));
		assertEquals(Set.of("user_id", "action", "session_id_hash", "session_version", "preview_digest", "proof_hash", "created_at", "expires_at"),
				Set.copyOf(jdbcTemplate.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'public' AND table_name = 'privileged_confirmation_proofs'", String.class)));
	}

	@Test
	void expiredOrOverlongProofsCannotReplaceTheCurrentValidProof() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		for (Instant expiresAt : new Instant[] { NOW, NOW.minusSeconds(1), NOW.plusSeconds(301) }) {
			assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(status -> proofStore.replaceCurrent(
					new Proof(userID, SESSION, VERSION, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, OTHER_HASH, expiresAt))));
		}
		assertTrue(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
	}

	@Test
	void databaseConstraintsEnforceTheHashActionAndFiveMinuteMaximum() {
		UUID userID = account();
		assertThrows(DataIntegrityViolationException.class, () -> insert(userID, "UNKNOWN_ACTION", HASH, NOW.plusSeconds(300)));
		assertThrows(DataIntegrityViolationException.class, () -> insert(userID, Action.STRUCTURE_ADMIN_TRANSFER.name(), "raw-token", NOW.plusSeconds(300)));
		assertThrows(DataIntegrityViolationException.class, () -> insert(userID, Action.STRUCTURE_ADMIN_TRANSFER.name(), HASH, NOW.plusSeconds(301)));
	}

	@Test
	void theSameHashCannotBeAssignedToTwoDifferentActions() {
		UUID userID = account();
		replace(userID, Action.SUPER_ADMIN_SUCCESSION_CREATE, HASH);
		assertThrows(DataIntegrityViolationException.class, () -> replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH));
		assertTrue(consume(userID, Action.SUPER_ADMIN_SUCCESSION_CREATE, HASH, NOW));
	}

	@Test
	void bothWritingAndConsumptionRequireTheBusinessTransaction() {
		UUID userID = account();
		assertThrows(IllegalTransactionStateException.class, () -> proofStore.replaceCurrent(
				new Proof(userID, SESSION, VERSION, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, HASH, NOW.plusSeconds(300))));
		assertThrows(IllegalTransactionStateException.class, () -> proofStore.consumeIfValid(userID, SESSION, VERSION,
				Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, HASH, NOW));
	}

	@Test
	void onlyOneConcurrentConsumerCanCommitTheConsumption() throws Exception {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		CyclicBarrier start = new CyclicBarrier(2);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW); });
			var second = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW); });
			assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
			assertEquals(0, count(userID));
		}
	}

	@Test
	void concurrentReplacementsLeaveExactlyOneUsableProof() throws Exception {
		UUID userID = account();
		CyclicBarrier start = new CyclicBarrier(2);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH); return null; });
			var second = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH); return null; });
			first.get(10, TimeUnit.SECONDS);
			second.get(10, TimeUnit.SECONDS);
		}
		assertEquals(1, count(userID));
		boolean first = consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW);
		boolean second = consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH, NOW);
		assertTrue(first ^ second);
	}

	@Test
	void rollbackRestoresTheProofAndCancelsTheBusinessWrite() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		String name = jdbcTemplate.queryForObject("SELECT user_name FROM user_accounts WHERE user_id = ?", String.class, userID);
		assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
			assertTrue(proofStore.consumeIfValid(userID, SESSION, VERSION, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, HASH, NOW));
			jdbcTemplate.update("UPDATE user_accounts SET user_name = ? WHERE user_id = ?", "fictitious_rolled_back", userID);
			throw new IllegalStateException("Échec métier fictif après consommation.");
		}));
		assertEquals(name, jdbcTemplate.queryForObject("SELECT user_name FROM user_accounts WHERE user_id = ?", String.class, userID));
		assertTrue(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
	}

	@Test
	void aFailedReplacementRestoresThePreviousProof() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
			proofStore.replaceCurrent(new Proof(userID, SESSION, VERSION, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, OTHER_HASH, NOW.plusSeconds(300)));
			throw new IllegalStateException("Échec fictif après remplacement.");
		}));
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, OTHER_HASH, NOW));
		assertTrue(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
	}

	@Test
	void expiryIsRecheckedAfterWaitingForTheAccountLock() throws Exception {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
		try (var workers = Executors.newSingleThreadExecutor()) {
			var result = transaction.execute(status -> {
				jdbcTemplate.queryForObject("SELECT user_id FROM user_accounts WHERE user_id = ? FOR UPDATE", UUID.class, userID);
				int holdingPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				var pending = workers.submit(() -> transaction.execute(other -> {
					waitingPid.complete(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
					return proofStore.consumeIfValid(userID, SESSION, VERSION, Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, HASH, NOW);
				}));
				try {
					int pid = waitingPid.get(5, TimeUnit.SECONDS);
					long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
					while (!jdbcTemplate.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holdingPid, pid)) {
						if (System.nanoTime() >= end) { throw new AssertionError("Le consommateur n’a pas attendu le verrou."); }
						Thread.sleep(10);
					}
					TIME.set(NOW.plusSeconds(300));
					return pending;
				} catch (Exception failure) {
					throw new AssertionError(failure);
				}
			});
			assertFalse(result.get(10, TimeUnit.SECONDS));
		}
	}

	@Test
	void onlyTheSessionThatIssuedTheProofCanConsumeIt() {
		UUID userID = account();
		replace(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH);
		boolean accepted = transaction.execute(status -> proofStore.consumeIfValid(userID, "8".repeat(64), VERSION,
				Action.STRUCTURE_ADMIN_TRANSFER, PREVIEW, HASH, NOW));
		assertFalse(accepted);
		assertTrue(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
		assertFalse(consume(userID, Action.STRUCTURE_ADMIN_TRANSFER, HASH, NOW));
	}

	private static void replace(UUID userID, Action action, String hash) {
		transaction.executeWithoutResult(status -> proofStore.replaceCurrent(new Proof(userID, SESSION, VERSION, action, PREVIEW, hash, NOW.plusSeconds(300))));
	}

	private static boolean consume(UUID userID, Action action, String hash, Instant now) {
		return transaction.execute(status -> proofStore.consumeIfValid(userID, SESSION, VERSION, action, PREVIEW, hash, now));
	}

	private static UUID account() {
		UUID userID = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO user_accounts
				    (user_id, user_name, status, user_first_name, user_last_name, user_email, user_phone_number, session_version)
				VALUES (?, ?, 'ACTIVE', 'Fictitious', 'Confirmation', ?, '+12025550100', ?)
				""", userID, "fictitious_confirmation_" + userID, userID + "@example.invalid", VERSION);
		return userID;
	}

	private static int count(UUID userID) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM privileged_confirmation_proofs WHERE user_id = ?", Integer.class, userID);
	}

	private static void insert(UUID userID, String action, String hash, Instant expiresAt) {
		jdbcTemplate.update("""
				INSERT INTO privileged_confirmation_proofs (user_id, action, session_id_hash, session_version, preview_digest, proof_hash, created_at, expires_at)
				VALUES (?, ?, ?, ?, ?, ?, ?, ?)
				""", userID, action, SESSION, VERSION, PREVIEW, hash, OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC), OffsetDateTime.ofInstant(expiresAt, ZoneOffset.UTC));
	}
}
