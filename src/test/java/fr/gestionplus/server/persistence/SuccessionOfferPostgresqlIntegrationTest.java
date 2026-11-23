package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
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
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
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

import fr.gestionplus.server.account.SuccessionOffer;
import fr.gestionplus.server.account.SuccessionOffer.Status;
import fr.gestionplus.server.account.SuccessionOfferStore;
import fr.gestionplus.server.support.SuccessionOfferCreationProcess;

@Testcontainers
class SuccessionOfferPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
	private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
	private static final Clock CLOCK = new Clock() {
		@Override public ZoneId getZone() { return ZoneOffset.UTC; }
		@Override public Clock withZone(ZoneId zone) { return this; }
		@Override public Instant instant() { return TIME.get(); }
	};
	@Container
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
			.withDatabaseName("fictitious_succession_offers").withUsername("fictitious_migration")
			.withPassword(UUID.randomUUID().toString())
			.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
					new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));
	private static JdbcTemplate jdbcTemplate;
	private static JdbcTemplate secondJdbcTemplate;
	private static TransactionTemplate transaction;
	private static TransactionTemplate secondTransaction;
	private static SuccessionOfferStore offerStore;
	private static SuccessionOfferStore secondOfferStore;
	private static Participants existing;

	@BeforeAll
	static void upgradeFromV32AndPrepareTwoIndependentStoreInstances() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure().dataSource(source).locations("classpath:db/migration").target("32").load().migrate();
		jdbcTemplate = new JdbcTemplate(source);
		existing = participants();
		Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
		var manager = new DataSourceTransactionManager(source);
		transaction = new TransactionTemplate(manager);
		offerStore = store(jdbcTemplate, manager);
		var secondSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		secondJdbcTemplate = new JdbcTemplate(secondSource);
		var secondManager = new DataSourceTransactionManager(secondSource);
		secondTransaction = new TransactionTemplate(secondManager);
		secondOfferStore = store(secondJdbcTemplate, secondManager);
	}

	@BeforeEach
	void clearOnlyTheIsolatedOffers() {
		TIME.set(NOW);
		jdbcTemplate.update("DELETE FROM succession_offers");
	}

	@Test
	void upgradingV32PreservesTheOrganizationAndItsCurrentSuperAdmin() {
		assertEquals(existing.outgoing(), jdbcTemplate.queryForObject("SELECT super_admin_user_id FROM tenant_organizations WHERE tenant_id = ?", UUID.class, existing.tenant()));
		SuccessionOffer offer = offer(existing);
		create(offer);
		assertEquals(offer, offerStore.findById(offer.offerID()).orElseThrow());
	}

	@Test
	void storesTheExactParticipantsRoleListDigestStatusAndUtcDates() {
		Participants participants = participants();
		List<UUID> roles = List.of(UUID.randomUUID(), UUID.randomUUID());
		SuccessionOffer offer = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(),
				participants.tenant(), roles, "a".repeat(64), Status.PENDING, NOW, NOW.plusSeconds(86400));
		create(offer);
		assertEquals(offer, offerStore.findById(offer.offerID()).orElseThrow());
		assertTrue(offerStore.findById(UUID.randomUUID()).isEmpty());
	}

	@ParameterizedTest
	@EnumSource(value = Status.class, names = { "PENDING", "ACCEPTED" })
	void anUnexpiredOpenOfferPreventsAnotherOfferForTheSameOrganization(Status status) {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);
		if (status == Status.ACCEPTED) {
			assertTrue(change(first.offerID(), Status.PENDING, Status.ACCEPTED));
		}
		assertThrows(AccessDeniedException.class, () -> create(offer(participants)));
		assertEquals(1, count(participants.tenant()));
	}

	@ParameterizedTest
	@EnumSource(value = Status.class, names = { "PENDING", "ACCEPTED" })
	void anOfferExpiringAtTheCurrentInstantDoesNotBlockANewOfferOrLoseItsHistory(Status status) {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);
		if (status == Status.ACCEPTED) {
			assertTrue(change(first.offerID(), Status.PENDING, Status.ACCEPTED));
		}
		TIME.set(first.expiresAt());
		create(offer(participants));
		assertEquals(2, count(participants.tenant()));
		assertEquals(status, offerStore.findById(first.offerID()).orElseThrow().status());
	}

	@Test
	void cancellingAnOfferAllowsAnotherWithoutReopeningTheOldOffer() {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);
		boolean cancelled = transaction.execute(status -> offerStore.cancel(first.offerID(), Status.PENDING, Status.CANCELLED));
		assertTrue(cancelled);
		create(offer(participants));
		assertFalse(change(first.offerID(), Status.CANCELLED, Status.ACCEPTED));
		assertEquals(2, count(participants.tenant()));
	}

	@Test
	void completingAnAcceptedOfferAllowsAnotherOffer() {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);
		assertTrue(change(first.offerID(), Status.PENDING, Status.ACCEPTED));
		boolean completed = transaction.execute(status -> offerStore.complete(first.offerID(), Status.ACCEPTED, Status.COMPLETED));
		assertTrue(completed);
		create(offer(participants));
		assertEquals(2, count(participants.tenant()));
	}

	@Test
	void differentOrganizationsCanEachHaveAnOpenOffer() {
		Participants first = participants(), second = participants();
		create(offer(first));
		create(offer(second));
		assertEquals(1, count(first.tenant()));
		assertEquals(1, count(second.tenant()));
	}

	@Test
	void aFormerSuperAdminCannotCreateAnOffer() {
		Participants participants = participants();
		jdbcTemplate.update("UPDATE tenant_organizations SET super_admin_user_id = ? WHERE tenant_id = ?", account(), participants.tenant());
		assertThrows(AccessDeniedException.class, () -> create(offer(participants)));
		assertEquals(0, count(participants.tenant()));
	}

	@Test
	void expiryAndInitialStatusAreCheckedBeforeInsertion() {
		Participants participants = participants();
		SuccessionOffer expired = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(), participants.tenant(),
				List.of(), "a".repeat(64), Status.PENDING, NOW.minusSeconds(86400), NOW);
		assertThrows(AccessDeniedException.class, () -> create(expired));
		SuccessionOffer accepted = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(), participants.tenant(),
				List.of(), "a".repeat(64), Status.ACCEPTED, NOW, NOW.plusSeconds(86400));
		assertThrows(IllegalArgumentException.class, () -> create(accepted));
		assertEquals(0, count(participants.tenant()));
	}

	@Test
	void creationAndLockingReadsRequireTheBusinessTransaction() {
		SuccessionOffer offer = offer(participants());
		assertThrows(IllegalTransactionStateException.class, () -> offerStore.create(offer));
		assertThrows(IllegalTransactionStateException.class, () -> offerStore.findByIdForUpdate(offer.offerID()));
		assertThrows(IllegalTransactionStateException.class, () -> offerStore.updateStatus(offer.offerID(), Status.PENDING, Status.CANCELLED));
	}

	@Test
	void rollbackDoesNotReserveTheOrganizationOrLeaveAPartialOffer() {
		Participants participants = participants();
		assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
			offerStore.create(offer(participants));
			throw new IllegalStateException("Échec métier fictif.");
		}));
		assertEquals(0, count(participants.tenant()));
		create(offer(participants));
		assertEquals(1, count(participants.tenant()));
	}

	@Test
	void twoIndependentStoreInstancesCannotBothCreateAnOpenOffer() throws Exception {
		Participants participants = participants();
		CyclicBarrier start = new CyclicBarrier(2);
		try (var workers = Executors.newFixedThreadPool(2)) {
			var first = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return tryCreate(offerStore, transaction, offer(participants)); });
			var second = workers.submit(() -> { start.await(5, TimeUnit.SECONDS); return tryCreate(secondOfferStore, secondTransaction, offer(participants)); });
			assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
		}
		assertEquals(1, count(participants.tenant()));
	}

	@Test
	void theCurrentSuperAdminIsRecheckedAfterWaitingForTheOrganizationLock() throws Exception {
		Participants participants = participants();
		UUID newHolder = account();
		assertFalse(createAfterTenantWait(offer(participants), () -> jdbcTemplate.update(
				"UPDATE tenant_organizations SET super_admin_user_id = ? WHERE tenant_id = ?", newHolder, participants.tenant())));
		assertEquals(0, count(participants.tenant()));
	}

	@Test
	void anOfferThatExpiresWhileCreationWaitsDoesNotBlockItsSuccessor() throws Exception {
		Participants participants = participants();
		create(new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(), participants.tenant(),
				List.of(), "a".repeat(64), Status.PENDING, NOW, NOW.plusSeconds(1)));
		assertTrue(createAfterTenantWait(offer(participants), () -> TIME.set(NOW.plusSeconds(1))));
		assertEquals(2, count(participants.tenant()));
	}

	@Test
	void anIncomingOfferThatExpiresDuringTheLockWaitCannotBeInserted() throws Exception {
		Participants participants = participants();
		SuccessionOffer incoming = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(), participants.tenant(),
				List.of(), "a".repeat(64), Status.PENDING, NOW, NOW.plusSeconds(1));
		assertFalse(createAfterTenantWait(incoming, () -> TIME.set(NOW.plusSeconds(1))));
		assertEquals(0, count(participants.tenant()));
	}

	@Test
	void twoSeparateJvmsContendOnTheSameDatabaseLock() throws Exception {
		Participants participants = participants();
		Path directory = Files.createTempDirectory("gestionplus-succession-offer-jvms-");
		System.out.println("Preuves des créations sur deux JVM : " + directory);
		Process first = null, second = null;
		try {
			first = startNode(directory, "first", participants);
			second = startNode(directory, "second", participants);
			awaitReady(directory.resolve("first-ready"), first);
			awaitReady(directory.resolve("second-ready"), second);
			Files.writeString(directory.resolve("release"), "release");
			assertTrue(first.waitFor(20, TimeUnit.SECONDS));
			assertTrue(second.waitFor(20, TimeUnit.SECONDS));
			assertEquals(0, first.exitValue(), "La première JVM de création a échoué.");
			assertEquals(0, second.exitValue(), "La seconde JVM de création a échoué.");
			assertEquals(Set.of("CREATED", "REFUSED"), Set.of(Files.readString(directory.resolve("first-result")), Files.readString(directory.resolve("second-result"))));
			assertEquals(1, count(participants.tenant()));
		} finally {
			stop(first);
			stop(second);
		}
	}

	private static boolean createAfterTenantWait(SuccessionOffer offer, Runnable whileLocked) throws Exception {
		CompletableFuture<Integer> waitingPid = new CompletableFuture<>();
		try (var worker = Executors.newSingleThreadExecutor()) {
			var pending = transaction.execute(status -> {
				jdbcTemplate.queryForObject("SELECT tenant_id FROM tenant_organizations WHERE tenant_id = ? FOR UPDATE", UUID.class, offer.tenantID());
				int holdingPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				var result = worker.submit(() -> {
					try {
						return secondTransaction.execute(other -> {
							waitingPid.complete(secondJdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
							secondOfferStore.create(offer);
							return true;
						});
					} catch (AccessDeniedException denied) { return false; }
				});
				try {
					int pid = waitingPid.get(5, TimeUnit.SECONDS);
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
					while (!jdbcTemplate.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class, holdingPid, pid)) {
						if (System.nanoTime() >= deadline) { throw new AssertionError("Le créateur n’a pas attendu le verrou de l’organisation."); }
						Thread.sleep(10);
					}
					whileLocked.run();
					return result;
				} catch (Exception failure) { throw new AssertionError(failure); }
			});
			return pending.get(10, TimeUnit.SECONDS);
		}
	}

	private static SuccessionOfferStore store(JdbcTemplate jdbcTemplate, DataSourceTransactionManager manager) {
		ProxyFactory proxy = new ProxyFactory(new PostgresqlSuccessionOfferStore(jdbcTemplate, CLOCK));
		proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		return (SuccessionOfferStore) proxy.getProxy();
	}

	private static void create(SuccessionOffer offer) {
		transaction.executeWithoutResult(status -> offerStore.create(offer));
	}

	private static boolean tryCreate(SuccessionOfferStore store, TransactionTemplate transaction, SuccessionOffer offer) {
		try { transaction.executeWithoutResult(status -> store.create(offer)); return true; }
		catch (AccessDeniedException denied) { return false; }
	}

	private static boolean change(UUID id, Status expected, Status next) {
		return transaction.execute(status -> offerStore.updateStatus(id, expected, next));
	}

	private static SuccessionOffer offer(Participants participants) {
		return new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(), participants.tenant(),
				List.of(), "a".repeat(64), Status.PENDING, TIME.get(), TIME.get().plusSeconds(86400));
	}

	private static Participants participants() {
		UUID outgoing = account(), successor = account(), tenant = UUID.randomUUID();
		jdbcTemplate.update("INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, 'Fictitious succession', ?)", tenant, outgoing);
		return new Participants(tenant, outgoing, successor);
	}

	private static UUID account() {
		UUID userID = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO user_accounts (user_id, user_name, status, user_first_name, user_last_name, user_email, user_phone_number)
				VALUES (?, ?, 'ACTIVE', 'Fictitious', 'Succession', ?, '+12025550100')
				""", userID, "fictitious_offer_" + userID, userID + "@example.invalid");
		return userID;
	}

	private static int count(UUID tenantID) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM succession_offers WHERE tenant_id = ?", Integer.class, tenantID);
	}

	private static Process startNode(Path directory, String node, Participants participants) throws Exception {
		Properties settings = new Properties();
		settings.putAll(java.util.Map.of("url", POSTGRES.getJdbcUrl(), "user", POSTGRES.getUsername(), "password", POSTGRES.getPassword(),
				"tenant", participants.tenant().toString(), "outgoing", participants.outgoing().toString(), "successor", participants.successor().toString(),
				"directory", directory.toString(), "node", node, "now", NOW.toString()));
		Path configuration = directory.resolve(node + ".properties");
		try (var writer = Files.newBufferedWriter(configuration, StandardCharsets.UTF_8)) { settings.store(writer, null); }
		Path arguments = directory.resolve(node + ".args");
		Files.writeString(arguments, "-Xmx192m\n-classpath\n\"" + System.getProperty("java.class.path").replace("\\", "/")
				+ "\"\n" + SuccessionOfferCreationProcess.class.getName() + "\n\"" + configuration.toString().replace("\\", "/") + "\"\n");
		String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
		ProcessBuilder builder = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments);
		String systemRoot = builder.environment().get("SystemRoot");
		builder.environment().clear();
		if (systemRoot != null) { builder.environment().put("SystemRoot", systemRoot); }
		builder.environment().put("TEMP", directory.toString());
		builder.environment().put("TMP", directory.toString());
		Process process = builder.redirectErrorStream(true).redirectOutput(directory.resolve(node + ".log").toFile()).start();
		Files.writeString(directory.resolve(node + "-pid"), Long.toString(process.pid()));
		return process;
	}

	private static void awaitReady(Path marker, Process process) throws Exception {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
		while (!Files.exists(marker)) {
			assertTrue(process.isAlive(), "La JVM de création s’est arrêtée avant la barrière.");
			if (System.nanoTime() >= end) { throw new AssertionError("La JVM de création n’est pas prête."); }
			Thread.sleep(20);
		}
	}

	private static void stop(Process process) throws Exception {
		if (process != null && process.isAlive()) {
			process.destroy();
			if (!process.waitFor(3, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				assertTrue(process.waitFor(3, TimeUnit.SECONDS));
			}
		}
	}

	private record Participants(UUID tenant, UUID outgoing, UUID successor) { }
}
