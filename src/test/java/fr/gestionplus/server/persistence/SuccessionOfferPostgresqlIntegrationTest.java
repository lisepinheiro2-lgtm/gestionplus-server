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
import java.time.OffsetDateTime;
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
import org.springframework.dao.DataIntegrityViolationException;
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
import fr.gestionplus.server.account.SuccessionOffer.ClosureReason;
import fr.gestionplus.server.account.SuccessionConsent;
import fr.gestionplus.server.account.SuccessionConfirmation;
import fr.gestionplus.server.account.SuccessionRevision;
import fr.gestionplus.server.support.SuccessionFixtures;
import fr.gestionplus.server.account.SuccessionOffer.OfferStatus;
import fr.gestionplus.server.account.SuccessionOfferStore;
import fr.gestionplus.server.support.SuccessionOfferCreationProcess;

@Testcontainers
class SuccessionOfferPostgresqlIntegrationTest {

	private static final Instant NOW = Instant.parse("2026-11-23T12:00:00Z");
	private static final Instant LEGACY_CREATED = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
	private static final AtomicReference<Instant> TIME = new AtomicReference<>(NOW);
	private static final Clock CLOCK = new Clock() {
		@Override
		public ZoneId getZone() {
			return ZoneOffset.UTC;
		}

		@Override
		public Clock withZone(ZoneId zone) {
			return this;
		}

		@Override
		public Instant instant() {
			return TIME.get();
		}
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
	private static UUID legacyOfferID;
	private static UUID completedLegacyOfferID;

	@BeforeAll
	static void upgradeFromV32AndPrepareTwoIndependentStoreInstances() {
		var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
		Flyway.configure().dataSource(source).locations("classpath:db/migration").target("38").load().migrate();
		jdbcTemplate = new JdbcTemplate(source);
		Participants legacy = participants();
		legacyOfferID = legacyOffer(legacy, "ACCEPTED");
		completedLegacyOfferID = legacyOffer(legacy, "COMPLETED");
		OffsetDateTime created = NOW.atOffset(ZoneOffset.UTC);
		jdbcTemplate.update("""
				INSERT INTO succession_confirmations
				    (offer_id, user_id, session_version, session_id_hash, preview_digest, confirmed_at, expires_at)
				VALUES (?, ?, 0, ?, ?, ?, ?)
				""", legacyOfferID, legacy.outgoing(), "a".repeat(64), "b".repeat(64), created, created.plusMinutes(5));
		jdbcTemplate.update("""
				INSERT INTO privileged_confirmation_proofs
				    (user_id, action, session_version, preview_digest, proof_hash, created_at, expires_at, session_id_hash)
				VALUES (?, 'SUPER_ADMIN_SUCCESSION_CREATE', 0, ?, ?, ?, ?, ?),
				       (?, 'STRUCTURE_ADMIN_TRANSFER', 0, ?, ?, ?, ?, ?)
				""", legacy.outgoing(), "b".repeat(64), "c".repeat(64), created, created.plusMinutes(5), "a".repeat(64),
				legacy.outgoing(), "b".repeat(64), "d".repeat(64), created, created.plusMinutes(5), "a".repeat(64));
		Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
		var manager = new DataSourceTransactionManager(source);
		transaction = new TransactionTemplate(manager);
		offerStore = store(jdbcTemplate, manager);
		var secondSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(),
				POSTGRES.getPassword());
		secondJdbcTemplate = new JdbcTemplate(secondSource);
		var secondManager = new DataSourceTransactionManager(secondSource);
		secondTransaction = new TransactionTemplate(secondManager);
		secondOfferStore = store(secondJdbcTemplate, secondManager);
	}

	@BeforeEach
	void resetClock() {
		TIME.set(NOW);
	}

	@Test
	void upgradingV38ClosesLegacyOffersAndPreservesTheirHistoryWithoutInventingConsent() {
		SuccessionOffer legacy = offerStore.findById(legacyOfferID).orElseThrow();
		assertEquals(OfferStatus.CANCELLED, legacy.status());
		assertEquals(ClosureReason.LEGACY_INCOMPATIBLE, legacy.closureReason());
		assertTrue(legacy.closedAt() != null);
		assertEquals(null, legacy.currentRevision());
		assertEquals(LEGACY_CREATED.plusSeconds(86400), legacy.expiresAt());
		assertEquals(OfferStatus.COMPLETED, offerStore.findById(completedLegacyOfferID).orElseThrow().status());
		assertEquals(List.of(), revisions().findHistory(legacyOfferID));
		assertEquals(List.of(), consents().findByRevision(legacyOfferID, 1));
		assertEquals(1, jdbcTemplate.queryForObject(
				"SELECT count(*) FROM succession_legacy_confirmations WHERE offer_id = ?", Integer.class, legacyOfferID));
		assertEquals(List.of("STRUCTURE_ADMIN_TRANSFER"), jdbcTemplate.queryForList(
				"SELECT action FROM privileged_confirmation_proofs WHERE user_id = ?", String.class, legacy.outgoingUserID()));
		assertTrue(offerStore.findByParticipant(legacy.outgoingUserID()).contains(legacy));
		assertFalse(close(legacy, ClosureReason.CANCELLED_BY_OUTGOING));
	}

	private static UUID legacyOffer(Participants participants, String status) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update("""
				INSERT INTO succession_offers (offer_id, tenant_id, outgoing_user_id, successor_user_id,
				    admin_roles_to_transfer, preview_digest, status, created_at, expires_at)
				VALUES (?, ?, ?, ?, '{}'::uuid[], ?, ?, ?, ?)
				""", id, participants.tenant(), participants.outgoing(), participants.successor(), "b".repeat(64),
				status, LEGACY_CREATED.atOffset(ZoneOffset.UTC), LEGACY_CREATED.plusSeconds(86400).atOffset(ZoneOffset.UTC));
		return id;
	}

	@Test
	void storesTheExactParticipantsRoleListDigestStatusAndUtcDates() {
		Participants participants = participants();
		List<UUID> roles = List.of(UUID.randomUUID(), UUID.randomUUID());
		SuccessionOffer offer = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(),
				participants.successor(),
				participants.tenant(), roles, 1L, OfferStatus.PENDING, NOW, NOW.plusSeconds(86400), null, null);
		create(offer);
		assertEquals(offer, offerStore.findById(offer.offerID()).orElseThrow());
		assertTrue(offerStore.findById(UUID.randomUUID()).isEmpty());
	}

	@ParameterizedTest
	@EnumSource(value = OfferStatus.class, names = { "PENDING", "ACCEPTED" })
	void anUnexpiredOpenOfferPreventsAnotherOfferForTheSameOrganization(OfferStatus status) {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);

		if (status == OfferStatus.ACCEPTED) {
			assertTrue(accept(first));
		}

		assertThrows(AccessDeniedException.class, () -> create(offer(participants)));
		assertEquals(1, count(participants.tenant()));
	}

	@ParameterizedTest
	@EnumSource(value = OfferStatus.class, names = { "PENDING", "ACCEPTED" })
	void anOfferExpiringAtTheCurrentInstantDoesNotBlockANewOfferOrLoseItsHistory(OfferStatus status) {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);

		if (status == OfferStatus.ACCEPTED) {
			assertTrue(accept(first));
		}

		TIME.set(first.expiresAt());
		create(offer(participants));
		assertEquals(2, count(participants.tenant()));
		assertEquals(OfferStatus.EXPIRED, offerStore.findById(first.offerID()).orElseThrow().status());
		assertEquals(first.expiresAt(), offerStore.findById(first.offerID()).orElseThrow().closedAt());
	}

	@Test
	void cancellingAnOfferAllowsAnotherWithoutReopeningTheOldOffer() {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);
		boolean cancelled = close(first, ClosureReason.CANCELLED_BY_OUTGOING);
		assertTrue(cancelled);
		create(offer(participants));
		assertFalse(accept(first));
		assertEquals(2, count(participants.tenant()));
	}

	@Test
	void completingAnAcceptedOfferAllowsAnotherOffer() {
		Participants participants = participants();
		SuccessionOffer first = offer(participants);
		create(first);
		assertTrue(accept(first));
		confirm(first, NOW);
		boolean completed = complete(first, NOW);
		assertTrue(completed);
		create(offer(participants));
		assertEquals(2, count(participants.tenant()));
	}

	@Test
	void completionRequiresAnAuthorizationStrictlyInsideTheOfferLifetime() {
		Participants participants = participants();
		SuccessionOffer offer = offer(participants);
		create(offer);
		assertTrue(accept(offer));
		assertFalse(complete(offer, NOW));
		confirm(offer, offer.expiresAt().minusSeconds(10));
		assertFalse(complete(offer, offer.createdAt().minusSeconds(1)));
		assertFalse(complete(offer, offer.expiresAt()));
		TIME.set(offer.expiresAt().plusSeconds(1));
		assertTrue(complete(offer, offer.expiresAt().minusMillis(1)));
		assertFalse(complete(offer, offer.expiresAt().minusMillis(1)));
	}

	private boolean complete(SuccessionOffer offer, Instant authorizedAt) {
		return transaction.execute(status -> offerStore.complete(offer.offerID(), 1, authorizedAt));
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
		jdbcTemplate.update("UPDATE tenant_organizations SET super_admin_user_id = ? WHERE tenant_id = ?", account(),
				participants.tenant());
		assertThrows(AccessDeniedException.class, () -> create(offer(participants)));
		assertEquals(0, count(participants.tenant()));
	}

	@Test
	void expiryAndInitialStatusAreCheckedBeforeInsertion() {
		Participants participants = participants();
		SuccessionOffer expired = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(),
				participants.successor(), participants.tenant(),
				List.of(), 1L, OfferStatus.PENDING, NOW.minusSeconds(86400), NOW, null, null);
		assertThrows(AccessDeniedException.class, () -> create(expired));
		SuccessionOffer accepted = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(),
				participants.successor(), participants.tenant(),
				List.of(), 1L, OfferStatus.ACCEPTED, NOW, NOW.plusSeconds(86400), null, null);
		assertThrows(IllegalArgumentException.class, () -> create(accepted));
		assertEquals(0, count(participants.tenant()));
	}

	@Test
	void creationAndLockingReadsRequireTheBusinessTransaction() {
		SuccessionOffer offer = offer(participants());
		assertThrows(IllegalTransactionStateException.class, () -> offerStore.create(offer, initial(offer)));
		assertThrows(IllegalTransactionStateException.class, () -> offerStore.findByIdForUpdate(offer.offerID()));
		assertThrows(IllegalTransactionStateException.class,
				() -> offerStore.close(offer.offerID(), 1, ClosureReason.CANCELLED_BY_OUTGOING, NOW));
	}

	@Test
	void rollbackDoesNotReserveTheOrganizationOrLeaveAPartialOffer() {
		Participants participants = participants();
		assertThrows(IllegalStateException.class, () -> transaction.executeWithoutResult(status -> {
			var offer = offer(participants);
			offerStore.create(offer, initial(offer));
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
			var first = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return tryCreate(offerStore, transaction, offer(participants));
			});
			var second = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return tryCreate(secondOfferStore, secondTransaction, offer(participants));
			});
			assertTrue(first.get(10, TimeUnit.SECONDS) ^ second.get(10, TimeUnit.SECONDS));
		}

		assertEquals(1, count(participants.tenant()));
	}

	@Test
	void theCurrentSuperAdminIsRecheckedAfterWaitingForTheOrganizationLock() throws Exception {
		Participants participants = participants();
		UUID newHolder = account();
		assertFalse(createAfterTenantWait(offer(participants), () -> jdbcTemplate.update(
				"UPDATE tenant_organizations SET super_admin_user_id = ? WHERE tenant_id = ?", newHolder,
				participants.tenant())));
		assertEquals(0, count(participants.tenant()));
	}

	@Test
	void anOfferThatExpiresWhileCreationWaitsDoesNotBlockItsSuccessor() throws Exception {
		Participants participants = participants();
		create(new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(),
				participants.tenant(),
				List.of(), 1L, OfferStatus.PENDING, NOW.minusSeconds(86399), NOW.plusSeconds(1), null, null));
		assertTrue(createAfterTenantWait(offer(participants), () -> TIME.set(NOW.plusSeconds(1))));
		assertEquals(2, count(participants.tenant()));
	}

	@Test
	void anIncomingOfferThatExpiresDuringTheLockWaitCannotBeInserted() throws Exception {
		Participants participants = participants();
		SuccessionOffer incoming = new SuccessionOffer(UUID.randomUUID(), participants.outgoing(),
				participants.successor(), participants.tenant(),
				List.of(), 1L, OfferStatus.PENDING, NOW.minusSeconds(86399), NOW.plusSeconds(1), null, null);
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
			assertEquals(Set.of("CREATED", "REFUSED"), Set.of(Files.readString(directory.resolve("first-result")),
					Files.readString(directory.resolve("second-result"))));
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
				jdbcTemplate.queryForObject("SELECT tenant_id FROM tenant_organizations WHERE tenant_id = ? FOR UPDATE",
						UUID.class, offer.tenantID());
				int holdingPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				var result = worker.submit(() -> {

					try {
						return secondTransaction.execute(other -> {
							waitingPid.complete(
									secondJdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
							secondOfferStore.create(offer, initial(offer));
							return true;
						});
					} catch (AccessDeniedException denied) {
						return false;
					}

				});

				try {
					int pid = waitingPid.get(5, TimeUnit.SECONDS);
					long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);

					while (!jdbcTemplate.queryForObject("SELECT ? = ANY(pg_blocking_pids(?))", Boolean.class,
							holdingPid, pid)) {

						if (System.nanoTime() >= deadline) {
							throw new AssertionError("Le créateur n’a pas attendu le verrou de l’organisation.");
						}

						Thread.sleep(10);
					}

					whileLocked.run();
					return result;
				} catch (Exception failure) {
					throw new AssertionError(failure);
				}

			});
			return pending.get(10, TimeUnit.SECONDS);
		}

	}

	private static SuccessionOfferStore store(JdbcTemplate jdbcTemplate, DataSourceTransactionManager manager) {
		ProxyFactory proxy = new ProxyFactory(new PostgresqlSuccessionOfferStore(jdbcTemplate, CLOCK, new PostgresqlSuccessionRevisionStore(jdbcTemplate)));
		proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		return (SuccessionOfferStore) proxy.getProxy();
	}

	private static void create(SuccessionOffer offer) {
		transaction.executeWithoutResult(status -> offerStore.create(offer, initial(offer)));
	}

	private static boolean tryCreate(SuccessionOfferStore store, TransactionTemplate transaction,
			SuccessionOffer offer) {

		try {
			transaction.executeWithoutResult(status -> store.create(offer, initial(offer)));
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		}

	}

	private static SuccessionRevision initial(SuccessionOffer offer) {
		return SuccessionFixtures.revision(offer, 1, offer.adminRolesToTransfer(), offer.createdAt());
	}

	private static PostgresqlSuccessionRevisionStore revisions() {
		return new PostgresqlSuccessionRevisionStore(jdbcTemplate);
	}

	private static PostgresqlSuccessionConsentStore consents() {
		return new PostgresqlSuccessionConsentStore(jdbcTemplate, offerStore);
	}

	private static PostgresqlSuccessionConfirmationStore confirmations() {
		return new PostgresqlSuccessionConfirmationStore(jdbcTemplate, offerStore);
	}

	private static boolean accept(SuccessionOffer offer) {
		return transaction.execute(status -> {
			consents().saveIfAbsent(new SuccessionConsent(offer.offerID(), offer.outgoingUserID(), 1, NOW));
			consents().saveIfAbsent(new SuccessionConsent(offer.offerID(), offer.successorUserID(), 1, NOW));
			return offerStore.markAccepted(offer.offerID(), 1, NOW);
		});
	}

	private static boolean close(SuccessionOffer offer, ClosureReason reason) {
		return transaction.execute(status -> offerStore.close(offer.offerID(), 1, reason, TIME.get()));
	}

	private static void confirm(SuccessionOffer offer, Instant at) {
		transaction.executeWithoutResult(status -> {
			for (UUID participant : List.of(offer.outgoingUserID(), offer.successorUserID())) {
				confirmations().replaceCurrent(new SuccessionConfirmation(offer.offerID(), participant, 1, 0,
						"e".repeat(64), initial(offer).previewDigest(), at, at.plusSeconds(300)));
			}
		});
	}

	@Test
	void revisionChangesInvalidateConfirmationsAndDoNotResurrectOldConsentWhenThePreviewReturns() {
		SuccessionOffer offer = offer(participants());
		create(offer);
		assertTrue(accept(offer));
		confirm(offer, NOW);
		TIME.set(NOW.plusSeconds(3));
		SuccessionRevision next = SuccessionFixtures.revision(offer, 2, List.of(UUID.randomUUID()), TIME.get());
		assertEquals(true, transaction.execute(status -> offerStore.advanceRevision(offer.offerID(), 1, next)));
		assertEquals(OfferStatus.PENDING, offerStore.findById(offer.offerID()).orElseThrow().status());
		assertEquals(offer.expiresAt(), offerStore.findById(offer.offerID()).orElseThrow().expiresAt());
		assertTrue(confirmations().findByOfferIDAndUserID(offer.offerID(), 1, offer.outgoingUserID()).isEmpty());
		assertEquals(2, consents().findByRevision(offer.offerID(), 1).size());
		assertEquals(List.of(), consents().findByRevision(offer.offerID(), 2));
		assertEquals(false, transaction.execute(status -> consents().saveIfAbsent(
				new SuccessionConsent(offer.offerID(), offer.outgoingUserID(), 1, TIME.get()))));
		assertEquals(false, transaction.execute(status -> offerStore.markAccepted(offer.offerID(), 2, TIME.get())));

		TIME.set(TIME.get().plusSeconds(1));
		SuccessionRevision returned = SuccessionFixtures.revision(offer, 3, List.of(), TIME.get());
		assertEquals(initial(offer).previewDigest(), returned.previewDigest());
		assertEquals(true, transaction.execute(status -> offerStore.advanceRevision(offer.offerID(), 2, returned)));
		assertEquals(List.of(initial(offer), next, returned), revisions().findHistory(offer.offerID()));
		assertEquals(List.of(), consents().findByRevision(offer.offerID(), 3));
	}

	@Test
	void acceptsAndConfirmsOneParticipantWhileTheOtherIsStillPending() {
		SuccessionOffer offer = offer(participants());
		create(offer);
		transaction.executeWithoutResult(status -> {
			var consent = new SuccessionConsent(offer.offerID(), offer.successorUserID(), 1, NOW);
			assertTrue(consents().saveIfAbsent(consent));
			assertFalse(consents().saveIfAbsent(consent));
			assertFalse(offerStore.markAccepted(offer.offerID(), 1, NOW));
			confirmations().replaceCurrent(new SuccessionConfirmation(offer.offerID(), offer.successorUserID(), 1, 0,
					"e".repeat(64), initial(offer).previewDigest(), NOW, NOW.plusSeconds(300)));
		});
		assertEquals(OfferStatus.PENDING, offerStore.findById(offer.offerID()).orElseThrow().status());
		assertEquals(1, consents().findByRevision(offer.offerID(), 1).size());
		assertTrue(confirmations().findByOfferIDAndUserID(offer.offerID(), 1, offer.successorUserID()).isPresent());
	}

	@Test
	void databaseRejectsHistoryEditsDeadlineChangesAndReopening() {
		SuccessionOffer offer = offer(participants());
		create(offer);
		assertTrue(accept(offer));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
				"UPDATE succession_revisions SET created_at = created_at + INTERVAL '1 second' WHERE offer_id = ?", offer.offerID()));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
				"DELETE FROM succession_consents WHERE offer_id = ?", offer.offerID()));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
				"UPDATE succession_offers SET expires_at = expires_at + INTERVAL '1 day' WHERE offer_id = ?", offer.offerID()));
		assertTrue(close(offer, ClosureReason.WITHDRAWN_BY_SUCCESSOR));
		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate.update(
				"UPDATE succession_offers SET status = 'PENDING', closure_reason = NULL, closed_at = NULL WHERE offer_id = ?", offer.offerID()));
	}

	private static SuccessionOffer offer(Participants participants) {
		return new SuccessionOffer(UUID.randomUUID(), participants.outgoing(), participants.successor(),
				participants.tenant(),
				List.of(), 1L, OfferStatus.PENDING, TIME.get(), TIME.get().plusSeconds(86400), null, null);
	}

	private static Participants participants() {
		UUID outgoing = account(), successor = account(), tenant = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, 'Fictitious succession', ?)",
				tenant, outgoing);
		return new Participants(tenant, outgoing, successor);
	}

	private static UUID account() {
		UUID userID = UUID.randomUUID();
		jdbcTemplate.update(
				"""
						INSERT INTO user_accounts (user_id, user_name, status, user_first_name, user_last_name, user_email, user_phone_number)
						VALUES (?, ?, 'ACTIVE', 'Fictitious', 'Succession', ?, '+12025550100')
						""",
				userID, "fictitious_offer_" + userID, userID + "@example.invalid");
		return userID;
	}

	private static int count(UUID tenantID) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM succession_offers WHERE tenant_id = ?", Integer.class,
				tenantID);
	}

	private static Process startNode(Path directory, String node, Participants participants) throws Exception {
		Properties settings = new Properties();
		settings.putAll(java.util.Map.of("url", POSTGRES.getJdbcUrl(), "user", POSTGRES.getUsername(), "password",
				POSTGRES.getPassword(),
				"tenant", participants.tenant().toString(), "outgoing", participants.outgoing().toString(), "successor",
				participants.successor().toString(),
				"directory", directory.toString(), "node", node, "now", NOW.toString()));
		Path configuration = directory.resolve(node + ".properties");

		try (var writer = Files.newBufferedWriter(configuration, StandardCharsets.UTF_8)) {
			settings.store(writer, null);
		}

		Path arguments = directory.resolve(node + ".args");
		Files.writeString(arguments,
				"-Xmx192m\n-classpath\n\"" + System.getProperty("java.class.path").replace("\\", "/")
						+ "\"\n" + SuccessionOfferCreationProcess.class.getName() + "\n\""
						+ configuration.toString().replace("\\", "/") + "\"\n");
		String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
		ProcessBuilder builder = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin", executable).toString(), "@" + arguments);
		String systemRoot = builder.environment().get("SystemRoot");
		builder.environment().clear();

		if (systemRoot != null) {
			builder.environment().put("SystemRoot", systemRoot);
		}

		builder.environment().put("TEMP", directory.toString());
		builder.environment().put("TMP", directory.toString());
		Process process = builder.redirectErrorStream(true).redirectOutput(directory.resolve(node + ".log").toFile())
				.start();
		Files.writeString(directory.resolve(node + "-pid"), Long.toString(process.pid()));
		return process;
	}

	private static void awaitReady(Path marker, Process process) throws Exception {
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);

		while (!Files.exists(marker)) {
			assertTrue(process.isAlive(), "La JVM de création s’est arrêtée avant la barrière.");

			if (System.nanoTime() >= end) {
				throw new AssertionError("La JVM de création n’est pas prête.");
			}

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

	private record Participants(UUID tenant, UUID outgoing, UUID successor) {
	}
}
