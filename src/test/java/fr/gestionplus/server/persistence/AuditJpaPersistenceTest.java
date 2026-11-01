
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import jakarta.persistence.EntityManager;

class AuditJpaPersistenceTest {

	private Connection connection;
	private StandardServiceRegistry registry;
	private SessionFactory sessionFactory;
	private EntityManager entityManager;
	private AuditEventJpaRepository repository;
	private JpaAuditStore auditStore;

	@BeforeEach
	void opensIsolatedInMemoryDatabase() throws Exception {

		connection = DriverManager
				.getConnection("jdbc:h2:mem:gestionplus_audit_" + UUID.randomUUID() + ";MODE=PostgreSQL");

		try (Statement statement = connection.createStatement()) {
			statement.execute("CREATE DOMAIN TIMESTAMPTZ AS TIMESTAMP WITH TIME ZONE");
		}

		ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V7__create_audit_tables.sql"));

		registry = new StandardServiceRegistryBuilder().clearSettings()
				.applySetting("hibernate.connection.datasource", new SingleConnectionDataSource(connection, true))
				.applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.applySetting("hibernate.hbm2ddl.auto", "validate").build();

		sessionFactory = new MetadataSources(registry).addAnnotatedClass(AuditEventEntity.class)
				.addAnnotatedClass(AuditTargetEmbeddable.class).buildMetadata().buildSessionFactory();

		entityManager = sessionFactory.createEntityManager();

		JpaRepositoryFactory factory = new JpaRepositoryFactory(entityManager);

		repository = factory.getRepository(AuditEventJpaRepository.class);

		auditStore = new JpaAuditStore(repository);

		entityManager.getTransaction().begin();
	}

	@AfterEach
	void closesIsolatedDatabase() throws Exception {

		try {
			if (entityManager != null && entityManager.isOpen()) {
				
				try {
					if (entityManager.getTransaction().isActive()) {
						entityManager.getTransaction().rollback();
					}
				} finally {
					entityManager.close();
				}
			}
		} finally {

			try {
				if (sessionFactory != null) {
					sessionFactory.close();
				}
			} finally {

				try {
					if (registry != null) {
						StandardServiceRegistryBuilder.destroy(registry);
					}
				} finally {
					if (connection != null) {
						connection.close();
					}
				}
			}
		}
	}

	@Test
	void savesAndFindsEventWithMultipleTargets() {

		UUID eventId = UUID.randomUUID();

		Set<AuditTarget> targets = Set.of(new AuditTarget(AuditTargetType.EMPLOYEE, UUID.randomUUID()),
				new AuditTarget(AuditTargetType.ORGANIZATIONAL_UNIT, UUID.randomUUID()));

		AuditEvent expected = createEvent(eventId, UUID.randomUUID(), AuditAction.EMPLOYEE_UNIT_CHANGE,
				AuditResult.SUCCESS, "192.0.2.10", targets);

		auditStore.save(expected);
		flushAndClear();

		AuditEvent restored = auditStore.findById(eventId).orElseThrow();

		assertEventEquals(expected, restored);
		assertEquals(2, restored.getTargets().size());
		assertEquals(1, repository.count());
	}

	@Test
	void savesFailedLoginWithoutAuthenticatedUser() {

		UUID eventId = UUID.randomUUID();
		AuditEvent expected = createEvent(eventId, null, AuditAction.AUTH_LOGIN, AuditResult.FAILURE, null, Set.of());

		auditStore.save(expected);
		flushAndClear();

		AuditEvent restored = auditStore.findById(eventId).orElseThrow();

		assertEventEquals(expected, restored);
		assertNull(restored.getActorUserId());
		assertNull(restored.getIpAddress());
		assertTrue(restored.getTargets().isEmpty());
	}

	@Test
	void returnsEmptyForUnknownEvent() {

		assertTrue(auditStore.findById(UUID.randomUUID()).isEmpty());
	}

	@Test
	void savesSeveralEventsForTheSameTarget() {

		AuditTarget target = new AuditTarget(AuditTargetType.EMPLOYEE, UUID.randomUUID());
		AuditEvent first = createEvent(UUID.randomUUID(), null, AuditAction.EMPLOYEE_CREATE, AuditResult.SUCCESS, null,
				Set.of(target));
		AuditEvent second = createEvent(UUID.randomUUID(), null, AuditAction.EMPLOYEE_UPDATE, AuditResult.SUCCESS, null,
				Set.of(target));

		auditStore.save(first);
		auditStore.save(second);
		flushAndClear();

		AuditEvent restoredFirst = auditStore.findById(first.getEventId()).orElseThrow();
		AuditEvent restoredSecond = auditStore.findById(second.getEventId()).orElseThrow();

		assertEventEquals(first, restoredFirst);
		assertEventEquals(second, restoredSecond);
		assertEquals(2, repository.count());
	}

	@Test
	void rejectsTargetWithoutExistingEvent() {

		assertThrows(SQLException.class, () -> {
			try (PreparedStatement statement = connection.prepareStatement(
					"INSERT INTO audit_targets " + "(event_id, target_type, target_id) " + "VALUES (?, ?, ?)")) {

				statement.setObject(1, UUID.randomUUID());
				statement.setString(2, AuditTargetType.EMPLOYEE.name());
				statement.setObject(3, UUID.randomUUID());

				statement.executeUpdate();
			}
		});
	}

	private AuditEvent createEvent(UUID eventId, UUID actorUserId, AuditAction action, AuditResult result,
			String ipAddress, Set<AuditTarget> targets) {

		return new AuditEvent(Instant.parse("2026-11-01T12:00:00Z"), eventId, actorUserId, action, result, ipAddress,
				targets);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private void assertEventEquals(AuditEvent expected, AuditEvent actual) {

		assertEquals(expected.getEventId(), actual.getEventId());
		assertEquals(expected.getOccurredAt(), actual.getOccurredAt());
		assertEquals(expected.getActorUserId(), actual.getActorUserId());
		assertEquals(expected.getAction(), actual.getAction());
		assertEquals(expected.getResult(), actual.getResult());
		assertEquals(expected.getIpAddress(), actual.getIpAddress());
		assertEquals(expected.getTargets(), actual.getTargets());
	}
}
