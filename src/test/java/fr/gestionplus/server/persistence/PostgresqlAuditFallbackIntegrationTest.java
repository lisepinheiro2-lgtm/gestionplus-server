package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditFallback;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditPersistence;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_audit_fallback_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true", "gestionplus.audit.fallback.key=false",
		"gestionplus.activation.expiry-enabled=false", "gestionplus.notifications.from=false" })
class PostgresqlAuditFallbackIntegrationTest {

	private static final String TEST_KEY = Base64.getEncoder().encodeToString(new byte[32]);

	@TempDir
	Path journalDirectory;

	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private AuditStore auditStore;

	private PostgresqlAuditFallback fallback;
	private TransactionTemplate transaction;

	@BeforeEach
	void prepareJournal() {
		fallback = openJournal(100);
		transaction = new TransactionTemplate(transactionManager);
	}

	@Test
	void usesPrimaryPostgresqlWhenAvailable() {
		AuditEvent event = event(AuditResult.SUCCESS);

		transaction.executeWithoutResult(status -> fallback.writeInBusinessTransaction(event));

		assertSameEvent(event, auditStore.findById(event.getEventId()).orElseThrow());
		assertEquals(0, pendingCount(event));
	}

	@Test
	void persistsAnEncryptedSuccessOnlyWithTheBusinessCommitAndReconcilesIt() {
		AuditEvent event = event(AuditResult.SUCCESS);

		transaction.executeWithoutResult(status -> {
			hidePrimaryTargets();
			fallback.writeInBusinessTransaction(event);
			assertEquals(1, pendingCount(event));
			assertTrue(auditStore.findById(event.getEventId()).isEmpty());
			jdbc.execute("ALTER TABLE unavailable_audit_targets RENAME TO audit_targets");
		});

		assertEquals(1, pendingCount(event));
		assertTrue(auditStore.findById(event.getEventId()).isEmpty());

		fallback.reconcile();
		fallback.reconcile();

		assertSameEvent(event, auditStore.findById(event.getEventId()).orElseThrow());
		assertEquals(0, pendingCount(event));
		assertEquals(1, primaryCount(event));
	}

	@Test
	void aRolledBackBusinessTransactionCannotLeaveAReconciledSuccess() {
		AuditEvent event = event(AuditResult.SUCCESS);

		transaction.executeWithoutResult(status -> {
			hidePrimaryTargets();
			fallback.writeInBusinessTransaction(event);
			assertEquals(1, pendingCount(event));
			status.setRollbackOnly();
		});
		fallback.reconcile();

		assertTrue(auditStore.findById(event.getEventId()).isEmpty());
		assertEquals(0, pendingCount(event));
	}

	@Test
	void refusesARequiredSuccessWhenNeitherDatabaseAuditPathCanWrite() {
		AuditEvent event = event(AuditResult.SUCCESS);
		AuditEvent earlier = event(AuditResult.SUCCESS);
		transaction.executeWithoutResult(status -> fallback.writeInBusinessTransaction(earlier));

		assertThrows(DataAccessException.class, () -> transaction.executeWithoutResult(status -> {
			jdbc.update("UPDATE audit_events SET ip_address = ? WHERE event_id = ?", "192.0.2.1", earlier.getEventId());
			hidePrimaryTargets();
			jdbc.execute("ALTER TABLE audit_pending_events RENAME TO unavailable_audit_pending_events");
			fallback.writeInBusinessTransaction(event);
		}));

		assertSameEvent(earlier, auditStore.findById(earlier.getEventId()).orElseThrow());
		assertTrue(auditStore.findById(event.getEventId()).isEmpty());
		assertEquals(0, pendingCount(event));
	}

	@Test
	void restoresAnIndependentDenialAfterRestartAndDoesNotDuplicateItOnReplay() throws Exception {
		AuditEvent event = event(AuditResult.DENIED);
		AuditStore unavailable = unavailableStore();
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("auditFallback", fallback);
		AuditPersistence persistence = new AuditPersistence(unavailable, beans.getBeanProvider(AuditFallback.class),
				transactionManager);

		persistence.write(event);
		Path entry = fileFor(event);
		byte[] encrypted = Files.readAllBytes(entry);
		assertFalse(new String(encrypted, StandardCharsets.ISO_8859_1).contains(event.getIpAddress()));
		assertTrue(auditStore.findById(event.getEventId()).isEmpty());

		PostgresqlAuditFallback restarted = openJournal(100);
		restarted.reconcile();
		assertFalse(Files.exists(entry));
		assertSameEvent(event, auditStore.findById(event.getEventId()).orElseThrow());

		Files.write(entry, encrypted);
		restarted.reconcile();

		assertEquals(1, primaryCount(event));
		assertFalse(Files.exists(entry));
	}

	@Test
	void retainsTheJournalEntryWhenTheSameIdHasADifferentContent() {
		AuditEvent event = event(AuditResult.DENIED);
		fallback.writeIndependent(event);
		AuditEvent conflicting = new AuditEvent(event.getOccurredAt(), event.getEventId(), null,
				event.getAction(), event.getResult(), "192.0.2.1", event.getTargets(), event.getMetadata(),
				event.getTenantId());
		transaction.executeWithoutResult(status -> auditStore.save(conflicting));

		fallback.reconcile();

		assertTrue(Files.exists(fileFor(event)));
		assertSameEvent(conflicting, auditStore.findById(event.getEventId()).orElseThrow());
	}

	@Test
	void keepsACorruptedEntryForInvestigationWithoutReplayingIt() throws Exception {
		AuditEvent event = event(AuditResult.DENIED);
		fallback.writeIndependent(event);

		Path entry = fileFor(event);
		byte[] corrupted = Files.readAllBytes(entry);
		corrupted[corrupted.length - 1] ^= 1;
		Files.write(entry, corrupted);

		fallback.reconcile();

		assertTrue(Files.exists(entry));
		assertEquals(0, primaryCount(event));
	}

	@Test
	void signalsSaturationAndKeepsTheFirstEvent() {
		PostgresqlAuditFallback smallJournal = openJournal(1);
		AuditEvent first = event(AuditResult.DENIED);
		AuditEvent second = event(AuditResult.DENIED);
		smallJournal.writeIndependent(first);

		assertThrows(IllegalStateException.class, () -> smallJournal.writeIndependent(second));

		assertTrue(Files.exists(fileFor(first)));
		assertFalse(Files.exists(fileFor(second)));
	}

	@Test
	void mandatoryStandaloneSuccessFailsClosedWhenAuditIsUnavailable() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		AuditPersistence persistence = new AuditPersistence(unavailableStore(),
				beans.getBeanProvider(AuditFallback.class), transactionManager);

		assertThrows(IllegalStateException.class, () -> persistence.write(event(AuditResult.SUCCESS)));
	}

	private AuditStore unavailableStore() {
		AuditStore store = mock(AuditStore.class);
		doThrow(new DataAccessResourceFailureException("Panne simulée")).when(store).save(any(AuditEvent.class));
		return store;
	}

	private PostgresqlAuditFallback openJournal(int capacity) {
		return new PostgresqlAuditFallback(jdbc, transactionManager, TEST_KEY, journalDirectory.toString(), capacity);
	}

	private void hidePrimaryTargets() {
		jdbc.execute("ALTER TABLE audit_targets RENAME TO unavailable_audit_targets");
	}

	private int primaryCount(AuditEvent event) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM audit_events WHERE event_id = ?", Integer.class,
				event.getEventId());
	}

	private int pendingCount(AuditEvent event) {
		return jdbc.queryForObject("SELECT COUNT(*) FROM audit_pending_events WHERE event_id = ?", Integer.class,
				event.getEventId());
	}

	private Path fileFor(AuditEvent event) {
		return journalDirectory.resolve(event.getEventId() + ".audit");
	}

	private AuditEvent event(AuditResult result) {
		return new AuditEvent(Instant.parse("2026-11-10T08:09:10.123456Z"), UUID.randomUUID(), UUID.randomUUID(),
				AuditAction.STRUCTURE_ACCESS_GRANT, result, "192.0.2.79",
				Set.of(new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, UUID.randomUUID())),
				result == AuditResult.DENIED ? AuditMetadata.because(AuditReason.OUTSIDE_TENANT)
						: AuditMetadata.empty(),
				UUID.randomUUID());
	}

	private void assertSameEvent(AuditEvent expected, AuditEvent actual) {
		assertEquals(expected.getEventId(), actual.getEventId());
		assertEquals(expected.getOccurredAt(), actual.getOccurredAt());
		assertEquals(expected.getActorUserId(), actual.getActorUserId());
		assertEquals(expected.getTenantId(), actual.getTenantId());
		assertEquals(expected.getAction(), actual.getAction());
		assertEquals(expected.getResult(), actual.getResult());
		assertEquals(expected.getIpAddress(), actual.getIpAddress());
		assertEquals(expected.getTargets(), actual.getTargets());
		assertEquals(expected.getMetadata(), actual.getMetadata());
	}
}
