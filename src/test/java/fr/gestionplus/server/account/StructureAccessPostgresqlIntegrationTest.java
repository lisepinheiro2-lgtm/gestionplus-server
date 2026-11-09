package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.UserAccountEntity;
import fr.gestionplus.server.persistence.UserAccountJpaRepository;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_structure_access_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true" })
class StructureAccessPostgresqlIntegrationTest {

	@Autowired
	private StructureAccessService service;
	@Autowired
	private AuditStore auditStore;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;

	private UserAccount actor;
	private UUID targetId;
	private UUID tenantId;
	private UUID structureId;

	@BeforeEach
	void createsActiveAccountsAndTenant() {

		actor = createAccount(UserRole.SUPER_ADMIN);
		targetId = createAccount(UserRole.EMPLOYEE).getUserID();
		tenantId = UUID.randomUUID();

		jdbcTemplate.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) " + "VALUES (?, ?, ?)",
				tenantId, "Entreprise A", actor.getUserID());
		jdbcTemplate.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", targetId, tenantId);
		structureId = createStructure("Structure A");

		authenticate();
	}

	@AfterEach
	void clearsServerContext() {
		SecurityContextHolder.clearContext();
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void superAdminWithoutLocalMembershipGrantsAndRevokesWithHistoricalRoleSnapshots() {

		UUID otherStructureId = createStructure("Structure B");
		jdbcTemplate.update("INSERT INTO structure_memberships (user_id, structure_id) VALUES (?, ?)", targetId,
				otherStructureId);
		jdbcTemplate.update("INSERT INTO structure_membership_roles (user_id, structure_id, role) VALUES (?, ?, ?)",
				targetId, otherStructureId, UserRole.ACCOUNTANT.name());

		Set<UserRole> roles = Set.of(UserRole.TEAM_LEAD, UserRole.EMPLOYEE);
		assertEquals(0L, membershipCount(actor.getUserID(), structureId));

		service.addStructureAccess(targetId, structureId, roles);

		AuditEvent grant = singleAudit(AuditAction.STRUCTURE_ACCESS_GRANT);
		assertEquals(1L, membershipCount(targetId, structureId));
		assertEquals(roles, storedRoles(structureId));
		assertEquals(actor.getUserID(), grant.getActorUserId());
		assertEquals(AuditResult.SUCCESS, grant.getResult());
		assertEquals(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, targetId),
				new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureId)), grant.getTargets());
		assertEquals(AuditMetadata.rolesGranted(roles), grant.getMetadata());
		assertEquals("object",
				jdbcTemplate.queryForObject("SELECT jsonb_typeof(metadata) FROM audit_events " + "WHERE event_id = ?",
						String.class, grant.getEventId()));

		service.revokeStructureAccess(targetId, structureId);

		AuditEvent revoke = singleAudit(AuditAction.STRUCTURE_ACCESS_REVOKE);
		assertEquals(AuditMetadata.rolesRevoked(roles), revoke.getMetadata());
		assertEquals(grant.getTargets(), revoke.getTargets());
		assertEquals(AuditResult.SUCCESS, revoke.getResult());
		assertEquals(AuditMetadata.rolesGranted(roles),
				auditStore.findById(grant.getEventId()).orElseThrow().getMetadata());
		assertEquals(0L, membershipCount(targetId, structureId));
		assertTrue(storedRoles(structureId).isEmpty());
		assertEquals(Set.of(UserRole.ACCOUNTANT), storedRoles(otherStructureId));
	}

	@Test
	void rollsBackTheMembershipWhenPostgresqlRejectsTheAuditInsert() {

		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("x".repeat(46));
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		DataIntegrityViolationException failure = assertThrows(DataIntegrityViolationException.class,
				() -> service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE)));

		SQLException cause = assertInstanceOf(SQLException.class, failure.getMostSpecificCause());
		assertEquals("22001", cause.getSQLState());
		assertEquals(0L, membershipCount(targetId, structureId));
		assertTrue(storedRoles(structureId).isEmpty());
		assertTrue(auditIds(AuditAction.STRUCTURE_ACCESS_GRANT).isEmpty());
	}

	@Test
	void concurrentGrantsLeaveOneMembershipAndOnlyTheWinningRoleSnapshot() throws Exception {

		ExecutorService workers = Executors.newFixedThreadPool(2);
		CyclicBarrier start = new CyclicBarrier(2);
		Set<UserRole> firstRoles = Set.of(UserRole.EMPLOYEE);
		Set<UserRole> secondRoles = Set.of(UserRole.TEAM_LEAD, UserRole.SECRETARY);

		try {
			Future<Boolean> first = workers.submit(() -> attemptGrant(start, firstRoles));
			Future<Boolean> second = workers.submit(() -> attemptGrant(start, secondRoles));
			boolean firstGranted = first.get(15, TimeUnit.SECONDS);
			boolean secondGranted = second.get(15, TimeUnit.SECONDS);

			assertTrue(firstGranted ^ secondGranted, "Un seul rattachement doit être créé");
			Set<UserRole> winningRoles = firstGranted ? firstRoles : secondRoles;

			assertEquals(1L, membershipCount(targetId, structureId));
			assertEquals(winningRoles, storedRoles(structureId));
			assertEquals(AuditMetadata.rolesGranted(winningRoles),
					singleAudit(AuditAction.STRUCTURE_ACCESS_GRANT).getMetadata());
		} finally {
			workers.shutdownNow();
		}
	}

	private boolean attemptGrant(CyclicBarrier start, Set<UserRole> roles) throws Exception {

		authenticate();

		try {
			start.await(5, TimeUnit.SECONDS);
			service.addStructureAccess(targetId, structureId, roles);
			return true;
		} catch (AccessDeniedException denied) {
			assertEquals("Cet employé est déjà rattaché à cette structure.", denied.getMessage());
			return false;
		} finally {
			SecurityContextHolder.clearContext();
		}
	}

	private void authenticate() {
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
	}

	private UserAccount createAccount(UserRole role) {

		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Utilisateur", 35,
				"test_" + userId + "@example.invalid", "0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE, Set.of(role),
				employee.getEmployeeID());
		accountRepository.saveAndFlush(new UserAccountEntity(account));

		return account;
	}

	private UUID createStructure(String name) {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO organizational_structures (structure_id, structure_name, tenant_id) " + "VALUES (?, ?, ?)",
				id, name, tenantId);
		return id;
	}

	private long membershipCount(UUID userId, UUID structure) {
		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM structure_memberships " + "WHERE user_id = ? AND structure_id = ?", Long.class,
				userId, structure);
	}

	private Set<UserRole> storedRoles(UUID structure) {
		return Set.copyOf(jdbcTemplate.query(
				"SELECT role FROM structure_membership_roles " + "WHERE user_id = ? AND structure_id = ?",
				(row, rowNumber) -> UserRole.valueOf(row.getString("role")), targetId, structure));
	}

	private List<UUID> auditIds(AuditAction action) {
		return jdbcTemplate.queryForList("SELECT event_id FROM audit_events WHERE actor_user_id = ? AND action = ?",
				UUID.class, actor.getUserID(), action.name());
	}

	private AuditEvent singleAudit(AuditAction action) {
		List<UUID> ids = auditIds(action);
		assertEquals(1, ids.size());
		return auditStore.findById(ids.getFirst()).orElseThrow();
	}
}
