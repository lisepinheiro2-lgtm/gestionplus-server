package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;

import java.sql.SQLException;
import java.time.Instant;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditFallback;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.employee.EmployeeResponsibilityService;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.UserAccountEntity;
import fr.gestionplus.server.persistence.UserAccountJpaRepository;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_structure_access_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true", "gestionplus.notifications.from=false" })
class StructureAccessPostgresqlIntegrationTest extends AuditStorageIntegrationTest {

	@Autowired
	private StructureAccessService service;
	@Autowired
	private AuditStore auditStore;
	@MockitoSpyBean
	private AuditFallback auditFallback;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;

	@Autowired
	private AccountStore accountStore;
	@Autowired
	private AccountProvisioningService provisioningService;
	@Autowired
	private AuthorizationService authorizationService;
	@Autowired
	private EmployeeResponsibilityService responsibilityService;
	@Autowired
	private SessionRegistry sessionRegistry;
	@Autowired
	private StructureMembershipStore membershipStore;

	private UserAccount actor;
	private UserAccount target;
	private UUID targetId;
	private UUID tenantId;
	private UUID structureId;

	@BeforeEach
	void createsActiveAccountsAndTenant() {

		actor = createAccount(UserRole.SUPER_ADMIN);

		tenantId = UUID.randomUUID();

		jdbcTemplate.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) " + "VALUES (?, ?, ?)",
				tenantId, "Entreprise A", actor.getUserID());
		joinTenant(actor.getUserID());
		structureId = createStructure("Structure A");
		target = createAccount(UserRole.EMPLOYEE, structureId);
		targetId = target.getUserID();
		joinTenant(targetId);

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
		service.addStructureAccess(targetId, otherStructureId, Set.of(UserRole.ACCOUNTANT));
		target = accountStore.findById(targetId).orElseThrow();
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, target);

		Set<UserRole> roles = Set.of(UserRole.TEAM_LEAD, UserRole.EMPLOYEE);
		assertEquals(0L, membershipCount(actor.getUserID(), structureId));

		service.addStructureAccess(targetId, structureId, roles);

		AuditEvent grant = auditIds(AuditAction.STRUCTURE_ACCESS_GRANT).stream()
				.map(id -> auditStore.findById(id).orElseThrow())
				.filter(event -> event.getTargets().contains(
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureId)))
				.findFirst().orElseThrow();
		assertTrue(authorizationService.canInStructure(targetId, structureId, Permission.VIEW_OWN_SCHEDULE));
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
		assertFalse(authorizationService.canInStructure(targetId, structureId, Permission.VIEW_OWN_SCHEDULE));
		assertTrue(authorizationService.canInStructure(targetId, otherStructureId, Permission.VIEW_ACCOUNTING));
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(targetId).orElseThrow().getStatu());
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

	@Test
	void disablesTheAccountAndExpiresAllSessionsAfterTheLastRevocation() {
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		target = accountStore.findById(targetId).orElseThrow();
		String firstSession = UUID.randomUUID().toString();
		String secondSession = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(firstSession, target);
		sessionRegistry.registerNewSession(secondSession, target);

		service.revokeStructureAccess(targetId, structureId);

		UserAccount disabled = accountStore.findById(targetId).orElseThrow();
		assertEquals(UserAccountStatus.DISABLED, disabled.getStatu());
		assertEquals(target.getSessionVersion() + 1, disabled.getSessionVersion());
		assertTrue(disabled.getRoles().isEmpty());
		assertTrue(authorizationService.hasTenantMembership(targetId, tenantId));
		assertTrue(sessionRegistry.getSessionInformation(firstSession).isExpired());
		assertTrue(sessionRegistry.getSessionInformation(secondSession).isExpired());
		assertThrows(AccessDeniedException.class,
				() -> service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE)));
		assertEquals(1, auditIds(AuditAction.ACCOUNT_DISABLE).size());
	}

	@Test
	void concurrentRevocationsAcrossStructuresDisableTheAccountExactlyOnce() throws Exception {
		UUID otherStructureID = createStructure("Structure B");
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		service.addStructureAccess(targetId, otherStructureID, Set.of(UserRole.EMPLOYEE));
		target = accountStore.findById(targetId).orElseThrow();
		int priorRevocations = auditIds(AuditAction.AUTH_SESSION_REVOKE).size();
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, target);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Void> first = workers.submit(() -> revokeConcurrently(start, structureId));
			Future<Void> second = workers.submit(() -> revokeConcurrently(start, otherStructureID));
			first.get(15, TimeUnit.SECONDS);
			second.get(15, TimeUnit.SECONDS);
		}

		assertEquals(0L, membershipCount(targetId, structureId));
		assertEquals(0L, membershipCount(targetId, otherStructureID));
		assertEquals(UserAccountStatus.DISABLED, accountStore.findById(targetId).orElseThrow().getStatu());
		assertEquals(2, auditIds(AuditAction.STRUCTURE_ACCESS_REVOKE).size());
		assertEquals(1, auditIds(AuditAction.ACCOUNT_DISABLE).size());
		assertEquals(priorRevocations + 2, auditIds(AuditAction.AUTH_SESSION_REVOKE).size());
		UserAccount disabled = accountStore.findById(targetId).orElseThrow();
		assertTrue(disabled.getRoles().isEmpty());
		assertEquals(target.getSessionVersion() + 2, disabled.getSessionVersion());
		assertTrue(authorizationService.hasTenantMembership(targetId, tenantId));
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
	}

	@Test
	void aConcurrentGrantCannotBypassDisablingTheLastAccess() throws Exception {
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		target = accountStore.findById(targetId).orElseThrow();
		UUID otherStructureID = createStructure("Structure B");
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, target);
		CyclicBarrier start = new CyclicBarrier(2);
		boolean granted;

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Void> revoke = workers.submit(() -> revokeConcurrently(start, structureId));
			Future<Boolean> grant = workers.submit(() -> {
				authenticate();

				try {
					start.await(5, TimeUnit.SECONDS);
					withLockTimeout(
							() -> service.addStructureAccess(targetId, otherStructureID, Set.of(UserRole.EMPLOYEE)));
					return true;
				} catch (AccessDeniedException denied) {
					return false;
				} finally {
					SecurityContextHolder.clearContext();
				}

			});
			revoke.get(15, TimeUnit.SECONDS);
			granted = grant.get(15, TimeUnit.SECONDS);
		}

		assertEquals(0L, membershipCount(targetId, structureId));
		assertEquals(granted ? 1L : 0L, membershipCount(targetId, otherStructureID));
		assertEquals(granted ? UserAccountStatus.ACTIVE : UserAccountStatus.DISABLED,
				accountStore.findById(targetId).orElseThrow().getStatu());
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(target.getSessionVersion() + (granted ? 2 : 1),
				accountStore.findById(targetId).orElseThrow().getSessionVersion());
		assertEquals(1, auditIds(AuditAction.STRUCTURE_ACCESS_REVOKE).size());
		assertEquals(granted ? 2 : 1, auditIds(AuditAction.STRUCTURE_ACCESS_GRANT).size());
		assertEquals(granted ? 0 : 1, auditIds(AuditAction.ACCOUNT_DISABLE).size());
		assertTrue(authorizationService.hasTenantMembership(targetId, tenantId));
	}

	@Test
	void rejectsAnActorWhoseSessionWasRevokedWhileWaitingForTheAccountLock() throws Exception {

		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			Future<Boolean> attempt = new TransactionTemplate(transactionManager).execute(transaction -> {
				accountStore.findByIdForUpdate(actor.getUserID()).orElseThrow();
				accountStore.advanceSessionVersion(actor.getUserID());
				int blockingPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);

				Future<Boolean> pending = worker.submit(() -> {
					authenticate();

					try {
						withLockTimeout(
								() -> service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE)));
						return true;
					} catch (AccessDeniedException denied) {
						return false;
					} finally {
						SecurityContextHolder.clearContext();
					}

				});

				awaitBlockedBy(blockingPid, pending);
				return pending;
			});

			assertFalse(attempt.get(15, TimeUnit.SECONDS));
		}

		assertEquals(0L, membershipCount(targetId, structureId));
		assertTrue(auditIds(AuditAction.STRUCTURE_ACCESS_GRANT).isEmpty());
		assertEquals(1L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events
				WHERE actor_user_id = ? AND action = 'STRUCTURE_ACCESS_GRANT' AND result = 'DENIED'
				""", Long.class, actor.getUserID()));
	}

	@Test
	void oppositeAccountOperationsRefuseProtectedAccountsWithoutDeadlocking() throws Exception {
		UserAccount firstAdmin = createAccount(UserRole.EMPLOYEE);
		UserAccount secondAdmin = createAccount(UserRole.EMPLOYEE);
		UUID otherStructureID = createStructure("Structure B");
		joinTenant(firstAdmin.getUserID());
		joinTenant(secondAdmin.getUserID());
		membershipStore.save(new StructureMembership(firstAdmin.getUserID(), structureId, Set.of(UserRole.ADMIN)));
		membershipStore
				.save(new StructureMembership(secondAdmin.getUserID(), otherStructureID, Set.of(UserRole.ADMIN)));
		membershipStore.save(
				new StructureMembership(secondAdmin.getUserID(), structureId, Set.of(UserRole.EMPLOYEE)));
		membershipStore.save(
				new StructureMembership(firstAdmin.getUserID(), otherStructureID, Set.of(UserRole.EMPLOYEE)));
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> changeRolesConcurrently(start, firstAdmin,
					secondAdmin.getUserID(), structureId));
			Future<Boolean> second = workers.submit(() -> changeRolesConcurrently(start, secondAdmin,
					firstAdmin.getUserID(), otherStructureID));
			assertFalse(first.get(15, TimeUnit.SECONDS));
			assertFalse(second.get(15, TimeUnit.SECONDS));
		}

		assertEquals(Set.of(UserRole.EMPLOYEE),
				membershipStore.findByUserAndStructure(secondAdmin.getUserID(), structureId).orElseThrow().getRoles());
		assertEquals(Set.of(UserRole.EMPLOYEE),
				membershipStore.findByUserAndStructure(firstAdmin.getUserID(), otherStructureID).orElseThrow()
						.getRoles());
		assertEquals(Set.of(UserRole.ADMIN),
				membershipStore.findByUserAndStructure(firstAdmin.getUserID(), structureId).orElseThrow().getRoles());
		assertEquals(Set.of(UserRole.ADMIN),
				membershipStore.findByUserAndStructure(secondAdmin.getUserID(), otherStructureID).orElseThrow()
						.getRoles());
	}

	private Boolean changeRolesConcurrently(CyclicBarrier start, UserAccount principal, UUID userID, UUID structureID)
			throws Exception {
		authenticate(principal);

		try {
			start.await(5, TimeUnit.SECONDS);
			withLockTimeout(() -> service.changeStructureRoles(userID, structureID, Set.of(UserRole.TEAM_LEAD)));
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		} finally {
			SecurityContextHolder.clearContext();
		}

	}

	@Test
	void rollsBackTheLastRevocationWithoutExpiringSessionsWhenAuditFails() {
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		target = accountStore.findById(targetId).orElseThrow();
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, target);
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("x".repeat(46));
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		assertThrows(DataIntegrityViolationException.class,
				() -> service.revokeStructureAccess(targetId, structureId));

		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(targetId).orElseThrow().getStatu());
		assertEquals(1L, membershipCount(targetId, structureId));
		assertFalse(sessionRegistry.getSessionInformation(sessionID).isExpired());
	}

	@Test
	void lastPreparedAccessQueuesNotificationAndDisablesThePendingAccount() {
		UUID pendingID = UUID.randomUUID();
		accountStore.createAccount(UserAccount.pending(pendingID, "Test", "Attente", "pending@example.invalid",
				"0600000000"));
		joinTenant(pendingID);
		service.addStructureAccess(pendingID, structureId, Set.of(UserRole.EMPLOYEE));
		assertFalse(authorizationService.canInStructure(pendingID, structureId, Permission.VIEW_OWN_SCHEDULE));

		service.revokeStructureAccess(pendingID, structureId);

		assertEquals(UserAccountStatus.DISABLED, accountStore.findById(pendingID).orElseThrow().getStatu());
		assertEquals(0L, membershipCount(pendingID, structureId));
		assertEquals(actor.getUserID(), jdbcTemplate.queryForObject("""
				SELECT actor_id FROM account_deactivation_notifications
				WHERE target_id = ? AND structure_id = ?
				""", UUID.class, pendingID, structureId));
	}

	@Test
	void changingManagerUpdatesTheScopeWithoutChangingEmployeeRoles() {
		UserAccount formerManager = createAccount(UserRole.EMPLOYEE);
		UserAccount nextManager = createAccount(UserRole.EMPLOYEE);
		joinTenant(formerManager.getUserID());
		joinTenant(nextManager.getUserID());
		service.addStructureAccess(formerManager.getUserID(), structureId, Set.of(UserRole.MANAGER));
		service.addStructureAccess(nextManager.getUserID(), structureId, Set.of(UserRole.MANAGER));
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		responsibilityService.changeResponsibleManager(structureId, target.getEmployeeID(), formerManager.getUserID());

		UserAccount formerManagerSession = accountStore.findById(formerManager.getUserID()).orElseThrow();
		UserAccount nextManagerSession = accountStore.findById(nextManager.getUserID()).orElseThrow();
		authenticate(formerManagerSession);
		service.changeStructureRoles(targetId, structureId, Set.of(UserRole.TEAM_LEAD));
		assertThrows(AccessDeniedException.class, () -> responsibilityService.changeResponsibleManager(
				structureId, target.getEmployeeID(), nextManager.getUserID()));

		authenticate();
		responsibilityService.changeResponsibleManager(structureId, target.getEmployeeID(), nextManager.getUserID());
		assertEquals(Set.of(UserRole.TEAM_LEAD), storedRoles(structureId));

		authenticate(formerManagerSession);
		assertThrows(AccessDeniedException.class,
				() -> service.changeStructureRoles(targetId, structureId, Set.of(UserRole.EMPLOYEE)));
		authenticate(nextManagerSession);
		service.changeStructureRoles(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(structureId));
		assertEquals(2, auditIds(AuditAction.EMPLOYEE_MANAGER_CHANGE).size());

		authenticate();
		service.changeStructureRoles(nextManager.getUserID(), structureId, Set.of(UserRole.EMPLOYEE));
		service.changeStructureRoles(nextManager.getUserID(), structureId, Set.of(UserRole.MANAGER));
		authenticate(accountStore.findById(nextManager.getUserID()).orElseThrow());
		assertThrows(AccessDeniedException.class,
				() -> service.changeStructureRoles(targetId, structureId, Set.of(UserRole.TEAM_LEAD)));
	}

	@Test
	void managerCanPrepareOnlyAnExplicitlyAssignedEmployeeAccount() {
		UserAccount manager = createAccount(UserRole.EMPLOYEE);
		joinTenant(manager.getUserID());
		service.addStructureAccess(manager.getUserID(), structureId, Set.of(UserRole.MANAGER));
		UserAccount managerSession = accountStore.findById(manager.getUserID()).orElseThrow();
		Employee newEmployee = new Employee(UUID.randomUUID(), "Test", "Attente", 35, "pending@example.invalid",
				"0600000000", null, structureId);
		employeeRepository.saveAndFlush(new EmployeeEntity(newEmployee));

		authenticate(managerSession);
		assertThrows(AccessDeniedException.class, () -> provisioningService.createPendingAccount(structureId,
				newEmployee.getEmployeeID(), newEmployee.getLastName(), newEmployee.getFirstName(),
				newEmployee.getEmail(), newEmployee.getPhoneNumber()));

		authenticate();
		responsibilityService.changeResponsibleManager(structureId, newEmployee.getEmployeeID(), manager.getUserID());
		authenticate(managerSession);
		UUID pendingID = provisioningService.createPendingAccount(structureId, newEmployee.getEmployeeID(),
				newEmployee.getLastName(), newEmployee.getFirstName(), newEmployee.getEmail(),
				newEmployee.getPhoneNumber());
		service.addStructureAccess(pendingID, structureId, Set.of(UserRole.EMPLOYEE));

		assertEquals(UserAccountStatus.PENDING_ACTIVATION, accountStore.findById(pendingID).orElseThrow().getStatu());
		assertEquals(1L, membershipCount(pendingID, structureId));
		assertFalse(authorizationService.canInStructure(pendingID, structureId, Permission.VIEW_OWN_SCHEDULE));
	}

	@Test
	void secretaryCanPrepareAnExistingLocalEmployeeButCannotCreateAnUnscopedAccount() {
		UserAccount secretary = createAccount(UserRole.EMPLOYEE);
		joinTenant(secretary.getUserID());
		service.addStructureAccess(secretary.getUserID(), structureId, Set.of(UserRole.SECRETARY));
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Attente", 35, "pending@example.invalid",
				"0600000000", null, structureId);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));
		authenticate(accountStore.findById(secretary.getUserID()).orElseThrow());

		UUID pendingID = provisioningService.createPendingAccount(structureId, employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());
		service.addStructureAccess(pendingID, structureId, Set.of(UserRole.TEAM_LEAD));

		assertEquals(UserAccountStatus.PENDING_ACTIVATION, accountStore.findById(pendingID).orElseThrow().getStatu());
		assertThrows(AccessDeniedException.class, () -> provisioningService.createPendingAccount(structureId, null,
				"Exemple", "Test", "other@example.invalid", "0600000000"));
		assertThrows(AccessDeniedException.class,
				() -> service.addStructureAccess(pendingID, createStructure("Autre"), Set.of(UserRole.EMPLOYEE)));
	}

	@Test
	void superAdminCannotCreateAnAccountWithoutAnEmployee() {
		assertThrows(AccessDeniedException.class, () -> provisioningService.createPendingAccount(structureId, null,
				"Exemple", "Test", "other@example.invalid", "0600000000"));
	}

	@Test
	void secretaryManagesOrdinaryAccountsAndPreservesOtherStructures() {
		UserAccount secretary = createAccount(UserRole.EMPLOYEE);
		joinTenant(secretary.getUserID());
		service.addStructureAccess(secretary.getUserID(), structureId, Set.of(UserRole.SECRETARY));
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		UUID other = createStructure("Autre structure");
		service.addStructureAccess(targetId, other, Set.of(UserRole.EMPLOYEE));
		authenticate(accountStore.findById(secretary.getUserID()).orElseThrow());

		service.changeStructureRoles(targetId, structureId, Set.of(UserRole.TEAM_LEAD));
		assertEquals(Set.of(UserRole.TEAM_LEAD), storedRoles(structureId));
		assertThrows(AccessDeniedException.class,
				() -> service.changeStructureRoles(targetId, other, Set.of(UserRole.TEAM_LEAD)));
		service.revokeStructureAccess(targetId, structureId);

		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(other));
		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(targetId).orElseThrow().getStatu());
	}

	@Test
	void secretaryCannotModifyAStaffAccountWithAPrivilegedRoleElsewhere() {
		UserAccount secretary = createAccount(UserRole.EMPLOYEE);
		joinTenant(secretary.getUserID());
		service.addStructureAccess(secretary.getUserID(), structureId, Set.of(UserRole.SECRETARY));
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		UUID other = createStructure("Autre structure");
		service.addStructureAccess(targetId, other, Set.of(UserRole.MANAGER));
		authenticate(accountStore.findById(secretary.getUserID()).orElseThrow());

		assertThrows(AccessDeniedException.class,
				() -> service.changeStructureRoles(targetId, structureId, Set.of(UserRole.TEAM_LEAD)));

		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(structureId));
		assertEquals(Set.of(UserRole.MANAGER), storedRoles(other));
	}

	@ParameterizedTest
	@EnumSource(AccessChange.class)
	void everyEffectiveAccessChangeExpiresExistingSessionsOnlyAfterCommit(AccessChange change) {
		UUID preservedStructure = prepareAccessChange(change);
		UserAccount previous = accountStore.findById(targetId).orElseThrow();
		String firstSession = UUID.randomUUID().toString();
		String secondSession = UUID.randomUUID().toString();
		String actorSession = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(firstSession, previous);
		sessionRegistry.registerNewSession(secondSession, previous);
		sessionRegistry.registerNewSession(actorSession, actor);
		int priorRevocations = auditIds(AuditAction.AUTH_SESSION_REVOKE).size();

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			changeAccess(change);

			assertFalse(sessionRegistry.getSessionInformation(firstSession).isExpired());
			assertFalse(sessionRegistry.getSessionInformation(secondSession).isExpired());
		});

		UserAccount current = accountStore.findById(targetId).orElseThrow();
		assertEquals(UserAccountStatus.ACTIVE, current.getStatu());
		assertEquals(previous.getSessionVersion() + 1, current.getSessionVersion());
		assertTrue(sessionRegistry.getSessionInformation(firstSession).isExpired());
		assertTrue(sessionRegistry.getSessionInformation(secondSession).isExpired());
		assertFalse(sessionRegistry.getSessionInformation(actorSession).isExpired());
		assertEquals(actor.getSessionVersion(),
				accountStore.findById(actor.getUserID()).orElseThrow().getSessionVersion());
		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(preservedStructure));
		assertTrue(authorizationService.rolesInStructureForSession(previous, preservedStructure).isEmpty());
		assertEquals(Set.of(UserRole.EMPLOYEE),
				authorizationService.rolesInStructureForSession(current, preservedStructure));
		assertEquals(priorRevocations + 1, auditIds(AuditAction.AUTH_SESSION_REVOKE).size());
		assertTrue(auditIds(AuditAction.ACCOUNT_DISABLE).isEmpty());
		assertEquals(change == AccessChange.REVOKE ? Set.of() : Set.of(UserRole.TEAM_LEAD),
				storedRoles(structureId));

	}

	@ParameterizedTest
	@EnumSource(AccessChange.class)
	void aCallerRollbackPreservesRolesSessionVersionAndSessions(AccessChange change) {
		UUID preservedStructure = prepareAccessChange(change);
		UserAccount previous = accountStore.findById(targetId).orElseThrow();
		Set<UserRole> previousRoles = storedRoles(structureId);
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, previous);
		int priorRevocations = auditIds(AuditAction.AUTH_SESSION_REVOKE).size();

		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			changeAccess(change);
			assertFalse(sessionRegistry.getSessionInformation(sessionID).isExpired());
			transaction.setRollbackOnly();
		});

		assertEquals(previousRoles, storedRoles(structureId));
		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(preservedStructure));
		assertEquals(previous.getSessionVersion(), accountStore.findById(targetId).orElseThrow().getSessionVersion());
		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(targetId).orElseThrow().getStatu());
		assertFalse(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(priorRevocations, auditIds(AuditAction.AUTH_SESSION_REVOKE).size());
	}

	@ParameterizedTest
	@EnumSource(AccessChange.class)
	void aFailedSessionRevocationAuditRollsBackTheWholeAccessChange(AccessChange change) {
		UUID preservedStructure = prepareAccessChange(change);
		UserAccount previous = accountStore.findById(targetId).orElseThrow();
		Set<UserRole> previousRoles = storedRoles(structureId);
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, previous);
		int priorRevocations = auditIds(AuditAction.AUTH_SESSION_REVOKE).size();
		int priorGrants = auditIds(AuditAction.STRUCTURE_ACCESS_GRANT).size();
		IllegalStateException failure = new IllegalStateException("Échec d’audit simulé");

		doThrow(failure).when(auditFallback)
				.writeInBusinessTransaction(argThat(event -> event.getAction() == AuditAction.AUTH_SESSION_REVOKE
						&& event.getResult() == AuditResult.SUCCESS));

		assertSame(failure, assertThrows(IllegalStateException.class, () -> changeAccess(change)));
		assertEquals(previousRoles, storedRoles(structureId));
		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(preservedStructure));
		assertEquals(previous.getSessionVersion(), accountStore.findById(targetId).orElseThrow().getSessionVersion());
		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(targetId).orElseThrow().getStatu());
		assertFalse(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(priorRevocations, auditIds(AuditAction.AUTH_SESSION_REVOKE).size());
		assertEquals(priorGrants, auditIds(AuditAction.STRUCTURE_ACCESS_GRANT).size());
		assertTrue(auditIds(AuditAction.STRUCTURE_ACCESS_REVOKE).isEmpty());
		assertTrue(auditIds(AuditAction.STRUCTURE_ROLES_CHANGE).isEmpty());
	}

	@Test
	void unchangedRolesKeepTheSessionVersionAndExistingSessions() {
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		UserAccount previous = accountStore.findById(targetId).orElseThrow();
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, previous);
		int priorRevocations = auditIds(AuditAction.AUTH_SESSION_REVOKE).size();

		service.changeStructureRoles(targetId, structureId, Set.of(UserRole.EMPLOYEE));

		assertEquals(previous.getSessionVersion(), accountStore.findById(targetId).orElseThrow().getSessionVersion());
		assertFalse(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(priorRevocations, auditIds(AuditAction.AUTH_SESSION_REVOKE).size());
		assertTrue(auditIds(AuditAction.STRUCTURE_ROLES_CHANGE).isEmpty());
	}

	@ParameterizedTest
	@CsvSource({ "R_H,R_H", "R_H,ACCOUNTANT", "ADMIN,ADMIN", "SUPER_ADMIN,ADMIN" })
	void protectedRolesElsewherePreventEveryOrdinaryAdministrativeChange(
			UserRole operatorRole, UserRole protectedRole) {
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		UUID protectedStructure = createStructure("Structure protégée");
		UUID destination = createStructure("Structure de destination");
		membershipStore.save(new StructureMembership(targetId, protectedStructure, Set.of(protectedRole)));
		UserAccount operator = actor;

		if (operatorRole != UserRole.SUPER_ADMIN) {
			operator = createAccount(UserRole.EMPLOYEE);
			joinTenant(operator.getUserID());
			membershipStore.save(new StructureMembership(operator.getUserID(), structureId, Set.of(operatorRole)));
			membershipStore.save(new StructureMembership(operator.getUserID(), destination, Set.of(operatorRole)));
		}

		authenticate(operator);
		UserAccount previous = accountStore.findById(targetId).orElseThrow();
		String targetSession = UUID.randomUUID().toString();
		String operatorSession = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(targetSession, previous);
		sessionRegistry.registerNewSession(operatorSession, operator);

		assertThrows(AccessDeniedException.class,
				() -> service.changeStructureRoles(targetId, structureId, Set.of(UserRole.TEAM_LEAD)));
		assertThrows(AccessDeniedException.class, () -> service.revokeStructureAccess(targetId, structureId));
		assertThrows(AccessDeniedException.class,
				() -> service.addStructureAccess(targetId, destination, Set.of(UserRole.EMPLOYEE)));

		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(structureId));
		assertEquals(Set.of(protectedRole), storedRoles(protectedStructure));
		assertEquals(0L, membershipCount(targetId, destination));
		assertEquals(previous.getSessionVersion(), accountStore.findById(targetId).orElseThrow().getSessionVersion());
		assertFalse(sessionRegistry.getSessionInformation(targetSession).isExpired());
		assertFalse(sessionRegistry.getSessionInformation(operatorSession).isExpired());
		assertEquals(3L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events
				WHERE actor_user_id = ? AND result = 'DENIED'
				  AND action IN ('STRUCTURE_ACCESS_GRANT', 'STRUCTURE_ACCESS_REVOKE', 'STRUCTURE_ROLES_CHANGE')
				""", Long.class, operator.getUserID()));
	}

	@Test
	void aConcurrentPromotionProtectsTheWholeAccountAfterWaitingForItsLock() throws Exception {
		service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		UUID otherStructure = createStructure("Autre structure");
		service.addStructureAccess(targetId, otherStructure, Set.of(UserRole.EMPLOYEE));
		UserAccount operator = createAccount(UserRole.EMPLOYEE);
		joinTenant(operator.getUserID());
		service.addStructureAccess(operator.getUserID(), structureId, Set.of(UserRole.R_H));
		UserAccount operatorSession = accountStore.findById(operator.getUserID()).orElseThrow();
		long previousVersion = accountStore.findById(targetId).orElseThrow().getSessionVersion();

		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			Future<Boolean> attempt = new TransactionTemplate(transactionManager).execute(transaction -> {
				service.changeStructureRoles(targetId, otherStructure, Set.of(UserRole.R_H));
				int blockingPid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				Future<Boolean> pending = worker.submit(() -> {
					authenticate(operatorSession);

					try {
						withLockTimeout(
								() -> service.changeStructureRoles(targetId, structureId, Set.of(UserRole.TEAM_LEAD)));
						return true;
					} catch (AccessDeniedException denied) {
						return false;
					} finally {
						SecurityContextHolder.clearContext();
					}

				});

				awaitBlockedBy(blockingPid, pending);
				return pending;
			});

			assertFalse(attempt.get(15, TimeUnit.SECONDS));
		}

		assertEquals(Set.of(UserRole.EMPLOYEE), storedRoles(structureId));
		assertEquals(Set.of(UserRole.R_H), storedRoles(otherStructure));
		assertEquals(previousVersion + 1, accountStore.findById(targetId).orElseThrow().getSessionVersion());
		assertEquals(1L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events
				WHERE actor_user_id = ? AND action = 'STRUCTURE_ROLES_CHANGE' AND result = 'DENIED'
				""", Long.class, operator.getUserID()));
	}

	private UUID prepareAccessChange(AccessChange change) {
		UUID preservedStructure = createStructure("Structure conservée");
		service.addStructureAccess(targetId, preservedStructure, Set.of(UserRole.EMPLOYEE));

		if (change != AccessChange.GRANT) {
			service.addStructureAccess(targetId, structureId, Set.of(UserRole.EMPLOYEE));
		}

		return preservedStructure;
	}

	private void changeAccess(AccessChange change) {

		switch (change) {
		case GRANT -> {
			service.addStructureAccess(targetId, structureId, Set.of(UserRole.TEAM_LEAD));
		}
		case CHANGE -> {
			service.changeStructureRoles(targetId, structureId, Set.of(UserRole.TEAM_LEAD));
		}
		case REVOKE -> {
			service.revokeStructureAccess(targetId, structureId);
		}
		}

	}

	private void awaitBlockedBy(int blockingPid, Future<?> pending) {
		Instant deadline = Instant.now().plusSeconds(3);
		boolean waiting = false;

		while (!waiting && !pending.isDone() && Instant.now().isBefore(deadline)) {
			waiting = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
					SELECT EXISTS (
						SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
					)
					""", Boolean.class, blockingPid));

			if (!waiting) {

				try {
					Thread.sleep(20);
				} catch (InterruptedException interrupted) {
					Thread.currentThread().interrupt();
					throw new IllegalStateException(interrupted);
				}

			}

		}

		assertTrue(waiting, "La demande doit attendre le verrou PostgreSQL avant la relecture des droits.");
	}

	private enum AccessChange {
		GRANT, CHANGE, REVOKE
	}

	private void withLockTimeout(Runnable operation) {
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
			operation.run();
		});
	}

	private Void revokeConcurrently(CyclicBarrier start, UUID structureID) throws Exception {
		authenticate();

		try {
			start.await(5, TimeUnit.SECONDS);
			withLockTimeout(() -> service.revokeStructureAccess(targetId, structureID));
			return null;
		} finally {
			SecurityContextHolder.clearContext();
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
		authenticate(actor);
	}

	private void authenticate(UserAccount account) {
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
	}

	private UserAccount createAccount(UserRole role) {
		return createAccount(role, null);
	}

	private UserAccount createAccount(UserRole role, UUID employeeStructureID) {

		UUID userId = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Test", "Utilisateur", 35,
				"test_" + userId + "@example.invalid", "0600000000", null, employeeStructureID);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userId, "test_" + userId, UserAccountStatus.ACTIVE, Set.of(role),
				employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());
		accountRepository.saveAndFlush(new UserAccountEntity(account));

		return account;
	}

	private void joinTenant(UUID userID) {
		jdbcTemplate.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantId);
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

	@Test
	void recordsTheRefusalEvenWhenTheCallingBusinessTransactionRollsBack() {
		UUID outsideAccountId = createAccount(UserRole.EMPLOYEE).getUserID();
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);

		assertThrows(AccessDeniedException.class, () -> transaction.executeWithoutResult(status -> {
			jdbcTemplate.update("UPDATE organizational_structures SET structure_name = ? WHERE structure_id = ?",
					"Modification à annuler", structureId);
			service.addStructureAccess(outsideAccountId, structureId, Set.of(UserRole.EMPLOYEE));
		}));

		assertEquals("Structure A", jdbcTemplate.queryForObject(
				"SELECT structure_name FROM organizational_structures WHERE structure_id = ?", String.class,
				structureId));
		assertEquals(0L, membershipCount(outsideAccountId, structureId));
		assertTrue(auditIds(AuditAction.STRUCTURE_ACCESS_GRANT).isEmpty());

		UUID eventId = jdbcTemplate.queryForObject("""
				SELECT event_id FROM audit_events
				WHERE actor_user_id = ? AND action = 'STRUCTURE_ACCESS_GRANT' AND result = 'DENIED'
				""", UUID.class, actor.getUserID());
		AuditEvent event = auditStore.findById(eventId).orElseThrow();
		assertEquals(tenantId, event.getTenantId());
		assertEquals(AuditReason.OUTSIDE_TENANT, event.getMetadata().reason());
		assertEquals(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, outsideAccountId),
				new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureId)), event.getTargets());
	}

	private List<UUID> auditIds(AuditAction action) {
		return jdbcTemplate.queryForList(
				"SELECT event_id FROM audit_events WHERE actor_user_id = ? AND action = ? AND result = 'SUCCESS'",
				UUID.class, actor.getUserID(), action.name());
	}

	private AuditEvent singleAudit(AuditAction action) {
		List<UUID> ids = auditIds(action);
		assertEquals(1, ids.size());
		return auditStore.findById(ids.getFirst()).orElseThrow();
	}
}
