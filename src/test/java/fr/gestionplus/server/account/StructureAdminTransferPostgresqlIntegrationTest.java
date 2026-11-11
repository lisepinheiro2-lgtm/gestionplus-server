
package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

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
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import fr.gestionplus.server.account.StructureAdminTransferService.TransferPreview;
import fr.gestionplus.server.account.SuperAdminSuccessionService.SuccessionPreview;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditFallback;
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
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.TenantMembership;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.OrganizationalStructureEntity;
import fr.gestionplus.server.persistence.OrganizationalStructureJpaRepository;
import fr.gestionplus.server.persistence.StructureMembershipEntity;
import fr.gestionplus.server.persistence.StructureMembershipJpaRepository;
import fr.gestionplus.server.persistence.TenantMembershipEntity;
import fr.gestionplus.server.persistence.TenantMembershipJpaRepository;
import fr.gestionplus.server.persistence.TenantOrganizationEntity;
import fr.gestionplus.server.persistence.TenantOrganizationJpaRepository;
import fr.gestionplus.server.persistence.UserAccountEntity;
import fr.gestionplus.server.persistence.UserAccountJpaRepository;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_admin_transfer_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true", "gestionplus.notifications.from=false",
		"gestionplus.activation.expiry-enabled=false" })
class StructureAdminTransferPostgresqlIntegrationTest extends AuditStorageIntegrationTest {

	@Autowired
	private StructureAdminTransferService service;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;
	@Autowired
	private TenantOrganizationJpaRepository tenantRepository;
	@Autowired
	private OrganizationalStructureJpaRepository structureRepository;
	@Autowired
	private TenantMembershipJpaRepository tenantMembershipRepository;
	@Autowired
	private StructureMembershipJpaRepository membershipRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private AccountStore accountStore;
	@Autowired
	private AuthorizationService authorization;
	@Autowired
	private SuperAdminSuccessionService succession;
	@Autowired
	private SessionRegistry sessionRegistry;

	@MockitoBean
	private PrivilegedConfirmationVerifier verifier;

	@Autowired
	private AuditStore auditStore;

	@MockitoSpyBean
	private AuditFallback auditFallback;

	private UUID actorID;
	private UUID oldAdminUserID;
	private UUID newAdminUserID;
	private UUID tenantID;
	private UUID structureID;

	@BeforeEach
	void setUp() {

		SecurityContextHolder.clearContext();

		actorID = UUID.randomUUID();
		oldAdminUserID = UUID.randomUUID();
		newAdminUserID = UUID.randomUUID();
		tenantID = UUID.randomUUID();
		structureID = UUID.randomUUID();

		createAccount(actorID, UserRole.EMPLOYEE);
		createAccount(oldAdminUserID, UserRole.EMPLOYEE);
		createAccount(newAdminUserID, UserRole.EMPLOYEE);

		tenantRepository
				.saveAndFlush(new TenantOrganizationEntity(new TenantOrganization(tenantID, "Entreprise A", actorID)));

		structureRepository.saveAndFlush(
				new OrganizationalStructureEntity(new OrganizationalStructure(structureID, "Structure A", tenantID)));

		for (UUID userID : List.of(actorID, oldAdminUserID, newAdminUserID)) {
			tenantMembershipRepository.saveAndFlush(new TenantMembershipEntity(new TenantMembership(userID, tenantID)));
		}

		membershipRepository.saveAndFlush(new StructureMembershipEntity(
				new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN)), tenantID));

		authenticate(actorID);
		when(verifier.consumeRecentProof(any(), anyLong(), any(), anyString(), eq("verified-proof"))).thenReturn(true);
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void transfersAdminAndRecordsAudit() {

		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);
		assertTrue(preview.createsMembership());
		assertTrue(preview.incumbentWillBeDisabled());

		var result = service.transferStructureAdmin(preview, "verified-proof");

		assertEquals(UserAccountStatus.DISABLED, result.previousAdmin().status());
		assertEquals(Set.of(UserRole.ADMIN), result.newAdmin().rolesIn(structureID));
		assertEquals(0L, membershipCount(oldAdminUserID));
		assertTrue(rolesInStructure(oldAdminUserID).isEmpty());
		assertEquals(1L, membershipCount(newAdminUserID));
		assertEquals(Set.of("ADMIN"), rolesInStructure(newAdminUserID));
		assertEquals(1L, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM structure_membership_roles " + "WHERE structure_id = ? AND role = 'ADMIN'",
				Long.class, structureID));
		assertEquals("DISABLED", jdbcTemplate.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?",
				String.class, oldAdminUserID));

		UUID auditID = jdbcTemplate.queryForObject(
				"SELECT event_id FROM audit_events " + "WHERE actor_user_id = ? AND action = ? AND result = 'SUCCESS'",
				UUID.class, actorID, "STRUCTURE_ADMIN_TRANSFER");

		AuditEvent audit = auditStore.findById(auditID).orElseThrow();

		assertEquals(actorID, audit.getActorUserId());
		assertEquals(AuditAction.STRUCTURE_ADMIN_TRANSFER, audit.getAction());
		assertEquals(AuditResult.SUCCESS, audit.getResult());
		assertEquals(Set.of(new AuditTarget(AuditTargetType.ACCOUNT, oldAdminUserID),
				new AuditTarget(AuditTargetType.ACCOUNT, newAdminUserID),
				new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID)), audit.getTargets());
	}

	@Test
	void transfersOnlyAdminWhenBothUsersHaveOtherRoles() {
		membershipRepository.saveAndFlush(new StructureMembershipEntity(
				new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN, UserRole.EMPLOYEE)),
				tenantID));
		membershipRepository.saveAndFlush(new StructureMembershipEntity(
				new StructureMembership(newAdminUserID, structureID, Set.of(UserRole.R_H)), tenantID));

		UserAccount previousAdmin = accountStore.findById(oldAdminUserID).orElseThrow();
		UserAccount successor = accountStore.findById(newAdminUserID).orElseThrow();
		String oldSession = registerSession(previousAdmin);
		String successorSession = registerSession(successor);

		service.transferStructureAdmin(service.previewTransfer(structureID, newAdminUserID), "verified-proof");

		assertTrue(sessionRegistry.getSessionInformation(oldSession).isExpired());
		assertTrue(sessionRegistry.getSessionInformation(successorSession).isExpired());
		assertEquals(previousAdmin.getSessionVersion() + 1,
				accountStore.findById(oldAdminUserID).orElseThrow().getSessionVersion());
		assertEquals(successor.getSessionVersion() + 1,
				accountStore.findById(newAdminUserID).orElseThrow().getSessionVersion());
		assertEquals(Set.of(oldAdminUserID, newAdminUserID), Set.copyOf(jdbcTemplate.queryForList("""
				SELECT actor_id FROM account_deactivation_notifications
				WHERE structure_id = ? AND kind IN ('ADMIN_REMOVED', 'ADMIN_GRANTED')
				""", UUID.class, structureID)));
		assertEquals(Set.of("EMPLOYEE"), rolesInStructure(oldAdminUserID));
		assertEquals(Set.of("ADMIN", "R_H"), rolesInStructure(newAdminUserID));
	}

	@Test
	void rollsBackTransferWhenAuditFails() {
		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);
		IllegalStateException failure = new IllegalStateException("Échec d'audit simulé");

		doThrow(failure).when(auditFallback)
				.writeInBusinessTransaction(argThat(event -> event.getAction() == AuditAction.STRUCTURE_ADMIN_TRANSFER
						&& event.getResult() == AuditResult.SUCCESS));

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> service.transferStructureAdmin(preview, "verified-proof")));
		assertEquals(1L, membershipCount(oldAdminUserID));
		assertEquals(Set.of("ADMIN"), rolesInStructure(oldAdminUserID));
		assertEquals(0L, membershipCount(newAdminUserID));
		assertTrue(rolesInStructure(newAdminUserID).isEmpty());
		assertEquals(0L, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM audit_events "
						+ "WHERE actor_user_id = ? AND action = ? AND result = 'SUCCESS'",
				Long.class, actorID, "STRUCTURE_ADMIN_TRANSFER"));
	}

	@Test
	void refusesTransferWhenNoRecentProofWasProvided() {
		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(preview, "unverified"));
		assertEquals(Set.of("ADMIN"), rolesInStructure(oldAdminUserID));
		assertTrue(rolesInStructure(newAdminUserID).isEmpty());
	}

	@Test
	void refusesConfirmationWhenTheRecipientsRolesChanged() {
		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);
		saveMembership(newAdminUserID, structureID, Set.of(UserRole.EMPLOYEE));

		assertThrows(AccessDeniedException.class, () -> service.transferStructureAdmin(preview, "verified-proof"));

		assertEquals(Set.of("ADMIN"), rolesInStructure(oldAdminUserID));
		assertEquals(Set.of("EMPLOYEE"), rolesInStructure(newAdminUserID));
	}

	@Test
	void theSameSuperAdminCanTakeOverMultipleStructuresAndKeepTheDesignation() {
		UUID otherStructureID = createStructure();
		saveMembership(oldAdminUserID, otherStructureID, Set.of(UserRole.ADMIN));

		service.transferStructureAdmin(service.previewTransfer(structureID, actorID), "verified-proof");
		authenticate(actorID);
		service.transferStructureAdmin(service.previewTransfer(otherStructureID, actorID), "verified-proof");

		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(Set.of("ADMIN"), rolesInStructure(actorID));
		assertEquals(2L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM structure_membership_roles
				WHERE user_id = ? AND role = 'ADMIN'
				""", Long.class, actorID));
	}

	@Test
	void transferringTheSuperAdminsLastLocalRoleDoesNotDisableTheAccountOrChangeTheTitle() {
		service.transferStructureAdmin(service.previewTransfer(structureID, actorID), "verified-proof");
		authenticate(actorID);
		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);
		assertFalse(preview.incumbentWillBeDisabled());

		service.transferStructureAdmin(preview, "verified-proof");

		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(actorID).orElseThrow().getStatu());
		assertTrue(rolesInStructure(actorID).isEmpty());
		assertTrue(authorization.canInStructure(actorID, structureID, Permission.MANAGE_USERS));
	}

	@Test
	void successionKeepsSomeLocalAdminsAndTransfersOnlyTheConfirmedChoices() {
		UUID otherStructureID = createStructure();
		saveMembership(actorID, otherStructureID, Set.of(UserRole.ADMIN));
		service.transferStructureAdmin(service.previewTransfer(structureID, actorID), "verified-proof");
		authenticate(actorID);

		var preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of(structureID));
		assertFalse(preview.outgoingWillBeDisabled());
		assertThrows(AccessDeniedException.class,
				() -> succession.acceptSuccession(preview, "verified-proof", "verified-proof"));

		UserAccount outgoing = accountStore.findById(actorID).orElseThrow();
		UserAccount incoming = accountStore.findById(newAdminUserID).orElseThrow();
		String oldSession = registerSession(outgoing);
		String newSession = registerSession(incoming);
		authenticate(newAdminUserID);

		assertThrows(AccessDeniedException.class,
				() -> succession.acceptSuccession(preview, "verified-proof", "unverified"));
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());

		succession.acceptSuccession(preview, "verified-proof", "verified-proof");

		assertEquals(newAdminUserID,
				tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(Set.of("ADMIN"), rolesInStructure(newAdminUserID));
		assertTrue(rolesInStructure(actorID).isEmpty());
		assertTrue(authorization.canInStructure(actorID, otherStructureID, Permission.MANAGE_USERS));
		assertFalse(authorization.canInStructure(actorID, structureID, Permission.MANAGE_USERS));
		assertTrue(authorization.canInStructure(newAdminUserID, otherStructureID, Permission.MANAGE_USERS));
		assertTrue(sessionRegistry.getSessionInformation(oldSession).isExpired());
		assertTrue(sessionRegistry.getSessionInformation(newSession).isExpired());
		assertEquals(outgoing.getSessionVersion() + 1,
				accountStore.findById(actorID).orElseThrow().getSessionVersion());
		assertEquals(incoming.getSessionVersion() + 1,
				accountStore.findById(newAdminUserID).orElseThrow().getSessionVersion());
		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"""
								SELECT COUNT(*) FROM audit_events WHERE action = 'SUPER_ADMIN_SUCCESSION' AND actor_user_id = ? AND result = 'SUCCESS'
								""",
						Long.class, newAdminUserID));
	}

	@Test
	void refusesSuccessionWhenTheConfirmedSituationHasChanged() {
		var preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());
		saveMembership(newAdminUserID, structureID, Set.of(UserRole.EMPLOYEE));
		authenticate(newAdminUserID);

		assertThrows(AccessDeniedException.class,
				() -> succession.acceptSuccession(preview, "verified-proof", "verified-proof"));
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
	}

	@Test
	void twoConcurrentConfirmationsCannotMoveTheSameAdminRoleTwice() throws Exception {
		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> attemptTransfer(preview, start));
			Future<Boolean> second = workers.submit(() -> attemptTransfer(preview, start));
			assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
		}

		assertEquals(Set.of("ADMIN"), rolesInStructure(newAdminUserID));
		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"""
								SELECT COUNT(*) FROM audit_events WHERE action = 'STRUCTURE_ADMIN_TRANSFER' AND actor_user_id = ? AND result = 'SUCCESS'
								""",
						Long.class, actorID));
	}

	@Test
	void twoConcurrentAcceptancesCannotCompleteTheSameSuccessionTwice() throws Exception {
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> attemptSuccession(preview, start));
			Future<Boolean> second = workers.submit(() -> attemptSuccession(preview, start));
			assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
		}

		assertEquals(newAdminUserID,
				tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"""
								SELECT COUNT(*) FROM audit_events WHERE action = 'SUPER_ADMIN_SUCCESSION' AND actor_user_id = ? AND result = 'SUCCESS'
								""",
						Long.class, newAdminUserID));
		assertEquals(Set.of("ADMIN"), rolesInStructure(oldAdminUserID));
	}

	@Test
	void cannotDisableTheDesignatedSuperAdminWithoutTheLegacyRole() {
		assertThrows(InvalidDataAccessApiUsageException.class, () -> accountStore.disableAccount(actorID));

		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(actorID).orElseThrow().getStatu());
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
	}

	@Test
	void aFailedSuccessionAuditRollsBackTheHolderRolesVersionsAndNotifications() {
		service.transferStructureAdmin(service.previewTransfer(structureID, actorID), "verified-proof");
		authenticate(actorID);
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of(structureID));
		UserAccount outgoing = accountStore.findById(actorID).orElseThrow();
		UserAccount successor = accountStore.findById(newAdminUserID).orElseThrow();
		String outgoingSession = registerSession(outgoing);
		String successorSession = registerSession(successor);
		IllegalStateException failure = new IllegalStateException("Échec d’audit de succession.");
		doThrow(failure).when(auditFallback)
				.writeInBusinessTransaction(argThat(event -> event.getAction() == AuditAction.SUPER_ADMIN_SUCCESSION
						&& event.getResult() == AuditResult.SUCCESS));
		authenticate(newAdminUserID);

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> succession.acceptSuccession(preview, "verified-proof", "verified-proof")));

		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(Set.of("ADMIN"), rolesInStructure(actorID));
		assertTrue(rolesInStructure(newAdminUserID).isEmpty());
		assertEquals(UserAccountStatus.ACTIVE, accountStore.findById(actorID).orElseThrow().getStatu());
		assertEquals(outgoing.getSessionVersion(),
				accountStore.findById(actorID).orElseThrow().getSessionVersion());
		assertEquals(successor.getSessionVersion(),
				accountStore.findById(newAdminUserID).orElseThrow().getSessionVersion());
		assertFalse(sessionRegistry.getSessionInformation(outgoingSession).isExpired());
		assertFalse(sessionRegistry.getSessionInformation(successorSession).isExpired());
		assertEquals(0, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM account_deactivation_notifications
				WHERE target_id = ? AND structure_id = ? AND kind = 'ADMIN_GRANTED'
				""", Integer.class, newAdminUserID, structureID));
		assertEquals(0, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events
				WHERE actor_user_id = ? AND action = 'SUPER_ADMIN_SUCCESSION' AND result = 'SUCCESS'
				""", Integer.class, newAdminUserID));
	}

	private boolean attemptSuccession(SuccessionPreview preview, CyclicBarrier start) throws Exception {
		authenticate(newAdminUserID);

		try {
			start.await(5, TimeUnit.SECONDS);
			succession.acceptSuccession(preview, "verified-proof", "verified-proof");
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		} finally {
			SecurityContextHolder.clearContext();
		}

	}

	private boolean attemptTransfer(TransferPreview preview, CyclicBarrier start) throws Exception {
		authenticate(actorID);

		try {
			start.await(5, TimeUnit.SECONDS);
			service.transferStructureAdmin(preview, "verified-proof");
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		} finally {
			SecurityContextHolder.clearContext();
		}

	}

	private UUID createStructure() {
		UUID id = UUID.randomUUID();
		structureRepository.saveAndFlush(
				new OrganizationalStructureEntity(new OrganizationalStructure(id, "Autre structure", tenantID)));
		return id;
	}

	private void saveMembership(UUID userID, UUID structureID, Set<UserRole> roles) {
		membershipRepository.saveAndFlush(
				new StructureMembershipEntity(new StructureMembership(userID, structureID, roles), tenantID));
	}

	private void authenticate(UUID userID) {
		UserAccount account = accountStore.findById(userID).orElseThrow();
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of()));
	}

	private String registerSession(UserAccount account) {
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, account);
		return sessionID;
	}

	private UserAccount createAccount(UUID userID, UserRole role) {

		Employee employee = new Employee(UUID.randomUUID(), "Test", "Utilisateur", 35,
				"test_" + userID + "@example.invalid", "0600000000", null);

		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "test_" + userID, UserAccountStatus.ACTIVE, Set.of(role),
				employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(), employee.getEmail(),
				employee.getPhoneNumber());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		return account;
	}

	private long membershipCount(UUID userID) {

		return jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM structure_memberships " + "WHERE user_id = ? AND structure_id = ?", Long.class,
				userID, structureID);
	}

	private Set<String> rolesInStructure(UUID userID) {

		return Set.copyOf(jdbcTemplate.queryForList(
				"SELECT role FROM structure_membership_roles " + "WHERE user_id = ? AND structure_id = ?", String.class,
				userID, structureID));
	}
}
