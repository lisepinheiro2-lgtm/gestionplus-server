package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_audit_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true" })
class AuditPostgresqlIntegrationTest {

	@Autowired
	private Flyway flyway;

	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Autowired
	private AuditStore auditStore;

	@Autowired
	private AuditService auditService;

	@Test
	void appliesV7OnRealPostgresqlAndValidatesJpaMapping() {

		assertNotNull(auditService);

		assertTrue(
				Arrays.stream(flyway.info().applied()).anyMatch(
						migration -> migration.getVersion() != null && "7".equals(migration.getVersion().toString())),
				"Flyway doit avoir applique V7 sur PostgreSQL");

		Long tableCount = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.tables
				WHERE table_schema = 'public'
				  AND table_name IN ('audit_events', 'audit_targets')
				""", Long.class);

		assertEquals(2L, tableCount);

		Long foreignKeyCount = jdbcTemplate.queryForObject("""
				SELECT COUNT(*)
				FROM information_schema.table_constraints
				WHERE table_schema = 'public'
				  AND table_name = 'audit_targets'
				  AND constraint_type = 'FOREIGN KEY'
				""", Long.class);

		assertEquals(1L, foreignKeyCount);
	}

	@Test
	void savesAndFindsAuditEventWithMultipleTargetsOnPostgresql() {
		
		UUID eventId = UUID.randomUUID();
		UUID actorUserId = UUID.randomUUID();

		Set<AuditTarget> targets = Set.of(new AuditTarget(AuditTargetType.EMPLOYEE, UUID.randomUUID()),
				new AuditTarget(AuditTargetType.ORGANIZATIONAL_UNIT, UUID.randomUUID()));

		AuditEvent expected = new AuditEvent(Instant.parse("2026-11-01T13:00:00Z"), eventId, actorUserId,
				AuditAction.EMPLOYEE_UNIT_CHANGE, AuditResult.SUCCESS, "192.0.2.10", targets);

		auditStore.save(expected);
		AuditEvent restored = auditStore.findById(eventId).orElseThrow();

		assertEquals(expected.getEventId(), restored.getEventId());
		assertEquals(expected.getOccurredAt(), restored.getOccurredAt());
		assertEquals(expected.getActorUserId(), restored.getActorUserId());
		assertEquals(expected.getAction(), restored.getAction());
		assertEquals(expected.getResult(), restored.getResult());
		assertEquals(expected.getIpAddress(), restored.getIpAddress());
		assertEquals(expected.getTargets(), restored.getTargets());
	}

	@Test
	void savesFailedLoginWithoutAuthenticatedActorOnPostgresql() {
		
		UUID eventId = UUID.randomUUID();

		AuditEvent expected = new AuditEvent(Instant.parse("2026-11-01T13:30:00Z"), eventId, null,
				AuditAction.AUTH_LOGIN, AuditResult.FAILURE, null, Set.of());

		auditStore.save(expected);
		AuditEvent restored = auditStore.findById(eventId).orElseThrow();

		assertEquals(expected.getEventId(), restored.getEventId());
		assertEquals(expected.getOccurredAt(), restored.getOccurredAt());
		assertEquals(AuditAction.AUTH_LOGIN, restored.getAction());
		assertEquals(AuditResult.FAILURE, restored.getResult());
		assertNull(restored.getActorUserId());
		assertNull(restored.getIpAddress());
		assertTrue(restored.getTargets().isEmpty());
	}
}
