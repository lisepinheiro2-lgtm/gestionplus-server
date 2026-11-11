package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.employee.EmployeeResponsibilityService;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.UserCredentialEntity;
import fr.gestionplus.server.persistence.UserCredentialJpaRepository;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_invitation_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true", "gestionplus.activation.expiry-enabled=false",
		"gestionplus.notifications.from=false" })
class AccountInvitationPostgresqlIntegrationTest {

	@Autowired
	private AccountInvitationService invitations;
	@Autowired
	private AccountInvitationStore invitationStore;
	@Autowired
	private AccountStore accountStore;
	@Autowired
	private StructureAccessService accessService;
	@Autowired
	private StructureMembershipStore membershipStore;
	@Autowired
	private AuthorizationService authorizationService;
	@Autowired
	private EmployeeResponsibilityService responsibilityService;
	@Autowired
	private EmployeeJpaRepository employees;
	@Autowired
	private UserCredentialJpaRepository credentials;
	@Autowired
	private SessionRegistry sessionRegistry;
	@Autowired
	private JdbcTemplate jdbcTemplate;
	@Autowired
	private PlatformTransactionManager transactionManager;

	@MockitoBean
	private AccountInvitationSender sender;
	@MockitoBean
	private ActivationIdentityVerifier verifier;
	@MockitoBean
	private CredentialService credentialService;

	private final List<SentInvitation> sent = new ArrayList<>();
	private UUID tenantID;
	private UUID structureID;
	private UserAccount actor;

	@BeforeEach
	void setUp() {
		actor = createAccount(UserAccountStatus.ACTIVE, Set.of(UserRole.SUPER_ADMIN), null);
		tenantID = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, ?, ?)",
				tenantID, "Entreprise test", actor.getUserID());
		joinTenant(actor.getUserID());
		structureID = createStructure();
		authenticate(actor);
		allowDelivery();
	}

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void reintegrationKeepsDisabledUntilDeliveryAndRequiresTheHolderToActivate() {
		UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE), structureID);
		UUID userID = former.getUserID();
		joinTenant(userID);
		credentials.saveAndFlush(new UserCredentialEntity(new UserCredential(userID, "old-hash")));
		membershipStore.save(new StructureMembership(userID, structureID, Set.of(UserRole.EMPLOYEE)));

		Map<UUID, Set<UserRole>> roles = Map.of(structureID, Set.of(UserRole.TEAM_LEAD));
		doReturn(false).when(sender).send(anyString(), any(), anyString());

		assertFalse(invitations.inviteForReintegration(userID, structureID, roles, null).sent());
		assertEquals(UserAccountStatus.DISABLED, account(userID).getStatu());
		assertTrue(membershipStore.findByUser(userID).isEmpty());
		assertTrue(invitationStore.findByAccount(userID).isEmpty());

		membershipStore.save(new StructureMembership(userID, structureID, Set.of(UserRole.EMPLOYEE)));

		doAnswer(call -> {
			assertEquals(UserAccountStatus.DISABLED, account(userID).getStatu());
			assertTrue(membershipStore.findByUser(userID).isEmpty());
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		assertTrue(invitations.inviteForReintegration(userID, structureID, roles, "updated@example.invalid").sent());
		UserAccount pending = account(userID);
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, pending.getStatu());
		assertEquals(former.getEmployeeID(), pending.getEmployeeID());
		assertEquals(former.getLastName(), pending.getLastName());
		assertTrue(pending.getRoles().isEmpty());
		assertTrue(pending.getSessionVersion() > former.getSessionVersion());
		assertEquals(Set.of(UserRole.TEAM_LEAD), membershipStore.findByUserAndStructure(userID, structureID)
				.orElseThrow().getRoles());
		assertFalse(authorizationService.canInStructure(userID, structureID, Permission.VIEW_TEAM_SCHEDULE));

		SentInvitation message = sent.getLast();
		assertEquals("updated@example.invalid", message.email());
		Invitation invitation = invitationStore.findByAccount(userID).orElseThrow();
		assertNotEquals(message.token(), invitation.tokenDigest());
		assertFalse(invitation.expiresAt().isBefore(message.deliveredAt().plus(Duration.ofDays(7)).minusMillis(1)));

		SecurityContextHolder.clearContext();
		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "wrong-proof"));
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());

		allowActivation(userID, message);
		activate(userID, message, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertEquals("new-hash", credentials.findById(userID).orElseThrow().toDomain().getPasswordHash());
		assertTrue(authorizationService.canInStructure(userID, structureID, Permission.VIEW_TEAM_SCHEDULE));
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "verified-proof"));
	}

	@Test
	void unsuccessfulResendPreservesOldLinkAndSuccessfulResendInvalidatesIt() {
		UUID userID = preparePendingAccount();
		SentInvitation first = sendInitial(userID);
		Invitation initial = invitationStore.findByAccount(userID).orElseThrow();
		doReturn(false).when(sender).send(anyString(), any(), anyString());

		assertFalse(invitations.resendInvitation(userID, structureID).sent());
		assertEquals(initial, invitationStore.findByAccount(userID).orElseThrow());

		allowDelivery();
		assertTrue(invitations.resendInvitation(userID, structureID).sent());
		SentInvitation resent = sent.getLast();
		Invitation latest = invitationStore.findByAccount(userID).orElseThrow();
		assertNotEquals(first.token(), resent.token());
		assertNotEquals(first.invitationID(), resent.invitationID());
		assertTrue(invitationStore.findByID(first.invitationID()).isEmpty());
		assertFalse(latest.expiresAt().isBefore(resent.deliveredAt().plus(Duration.ofDays(7)).minusMillis(1)));
		assertThrows(AccessDeniedException.class, () -> activate(userID, first, "verified-proof"));

		allowActivation(userID, resent);
		activate(userID, resent, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
	}

	@Test
	void replacementFailureLeavesTheAccountPendingWithoutAnyUsableLink() {
		UUID userID = preparePendingAccount();
		SentInvitation first = sendInitial(userID);
		doReturn(false).when(sender).send(anyString(), any(), anyString());

		assertFalse(invitations.replaceInvitation(userID, structureID, "corrected@example.invalid").sent());

		assertEquals("corrected@example.invalid", account(userID).getEmail());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertEquals(1, membershipStore.findByUser(userID).size());
		Invitation pendingReplacement = invitationStore.findByAccount(userID).orElseThrow();
		assertNull(pendingReplacement.tokenDigest());
		assertNull(pendingReplacement.expiresAt());
		assertTrue(invitationStore.findByID(first.invitationID()).isEmpty());
		assertThrows(AccessDeniedException.class, () -> activate(userID, first, "verified-proof"));

		allowDelivery();
		assertTrue(invitations.replaceInvitation(userID, structureID, "corrected@example.invalid").sent());
		assertEquals("corrected@example.invalid", sent.getLast().email());
		assertEquals(sent.getLast().invitationID(), invitationStore.findByAccount(userID).orElseThrow().invitationID());
	}

	@Test
	void replacementRevocationSurvivesFailureOfTheSendingTransaction() {
		UUID userID = preparePendingAccount();
		SentInvitation first = sendInitial(userID);
		doThrow(new IllegalStateException("Échec simulé après invalidation"))
				.when(sender).send(anyString(), any(), anyString());

		assertThrows(IllegalStateException.class,
				() -> invitations.replaceInvitation(userID, structureID, "corrected@example.invalid"));

		assertTrue(invitationStore.findByID(first.invitationID()).isEmpty());
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
	}

	@Test
	void cancellationRemovesEveryPreparedMembershipAndKeepsAdministrativeTenantMembership() {
		UUID userID = preparePendingAccount();
		accessService.addStructureAccess(userID, createStructure(), Set.of(UserRole.TEAM_LEAD));
		sendInitial(userID);

		invitations.cancelInvitation(userID, structureID);

		assertDisabledWithoutAccess(userID);
	}

	@Test
	void lastAccessRevocationAlsoInvalidatesTheInvitation() {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);

		accessService.revokeStructureAccess(userID, structureID);

		assertDisabledWithoutAccess(userID);
		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "verified-proof"));
	}

	@Test
	void expirationRejectsActivationAndClearsAllPreparedAccess() {
		UUID userID = preparePendingAccount();
		accessService.addStructureAccess(userID, createStructure(), Set.of(UserRole.TEAM_LEAD));
		SentInvitation message = sendInitial(userID);
		jdbcTemplate.update("""
				UPDATE account_invitations SET expires_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
				WHERE invitation_id = ?
				""", message.invitationID());
		allowActivation(userID, message);
		SecurityContextHolder.clearContext();

		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "verified-proof"));
		invitations.expireInvitation(message.invitationID());

		assertDisabledWithoutAccess(userID);
	}

	@Test
	void compromisedActivationRevokesTheSessionsAndRecordsTheIncident() {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		activate(userID, message, "verified-proof");
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, account(userID));

		invitations.disableCompromisedActivation(userID, structureID);

		assertDisabledWithoutAccess(userID);
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(1L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events event
				JOIN audit_targets target ON event.event_id = target.event_id
				WHERE event.action = 'INVITATION_FRAUD_DISABLE' AND target.target_id = ?
				""", Long.class, message.invitationID()));
	}

	@Test
	void incidentDeactivationCannotRemoveAProtectedAdmin() {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		activate(userID, message, "verified-proof");
		jdbcTemplate.update("""
				INSERT INTO structure_membership_roles (user_id, structure_id, role)
				VALUES (?, ?, 'ADMIN')
				""", userID, structureID);

		assertThrows(AccessDeniedException.class, () -> invitations.disableCompromisedActivation(userID, structureID));

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertTrue(membershipStore.findByUserAndStructure(userID, structureID).orElseThrow()
				.getRoles().contains(UserRole.ADMIN));
	}

	@Test
	void managerCanOnlyPrepareReintegrationForAnExplicitlyAssignedEmployee() {
		UserAccount manager = createAccount(UserAccountStatus.ACTIVE, Set.of(), structureID);
		UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(), structureID);
		UUID userID = former.getUserID();
		joinTenant(manager.getUserID());
		joinTenant(userID);
		accessService.addStructureAccess(manager.getUserID(), structureID, Set.of(UserRole.MANAGER));

		UserAccount managerSession = account(manager.getUserID());
		authenticate(managerSession);
		Map<UUID, Set<UserRole>> roles = Map.of(structureID, Set.of(UserRole.EMPLOYEE));
		assertThrows(AccessDeniedException.class,
				() -> invitations.inviteForReintegration(userID, structureID, roles, null));

		authenticate(actor);
		responsibilityService.changeResponsibleManager(structureID, former.getEmployeeID(), manager.getUserID());
		authenticate(managerSession);
		assertThrows(AccessDeniedException.class, () -> invitations.inviteForReintegration(userID, structureID,
				Map.of(structureID, Set.of(UserRole.SECRETARY)), null));
		assertTrue(invitations.inviteForReintegration(userID, structureID, roles, null).sent());
	}

	@Test
	void reintegrationConcurrentWithTheLastRevocationKeepsTheAccountInactive() throws Exception {
		UserAccount former = createAccount(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE), structureID);
		UUID userID = former.getUserID();
		joinTenant(userID);
		accessService.addStructureAccess(userID, structureID, Set.of(UserRole.EMPLOYEE));
		UUID preparedStructure = createStructure();
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, former);
		CyclicBarrier start = new CyclicBarrier(2);
		boolean reintegrated;

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> preparation = workers.submit(() -> {
				authenticate(actor);

				try {
					start.await(5, TimeUnit.SECONDS);
					return new TransactionTemplate(transactionManager).execute(transaction -> {
						jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
						return invitations.inviteForReintegration(userID, preparedStructure,
								Map.of(preparedStructure, Set.of(UserRole.EMPLOYEE)), null).sent();
					});
				} catch (AccessDeniedException denied) {
					return false;
				} finally {
					SecurityContextHolder.clearContext();
				}

			});
			Future<?> revocation = workers.submit(() -> {
				authenticate(actor);

				try {
					start.await(5, TimeUnit.SECONDS);
					new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
						jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
						accessService.revokeStructureAccess(userID, structureID);
					});
					return null;
				} finally {
					SecurityContextHolder.clearContext();
				}

			});
			reintegrated = preparation.get(15, TimeUnit.SECONDS);
			revocation.get(15, TimeUnit.SECONDS);
		}

		assertEquals(reintegrated ? UserAccountStatus.PENDING_ACTIVATION : UserAccountStatus.DISABLED,
				account(userID).getStatu());
		assertTrue(account(userID).getRoles().isEmpty());
		assertTrue(membershipStore.findByUserAndStructure(userID, structureID).isEmpty());
		assertEquals(reintegrated, membershipStore.findByUserAndStructure(userID, preparedStructure).isPresent());
		assertEquals(reintegrated, invitationStore.findByAccount(userID).isPresent());
		assertFalse(authorizationService.canInStructure(userID, preparedStructure, Permission.VIEW_OWN_SCHEDULE));
		assertTrue(authorizationService.hasTenantMembership(userID, tenantID));
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(reintegrated ? 1L : 0L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events event JOIN audit_targets target ON event.event_id = target.event_id
				WHERE event.action = 'INVITATION_CREATE' AND event.result = 'SUCCESS' AND target.target_id = ?
				""", Long.class, userID));
	}

	@Test
	void twoConcurrentActivationsCannotConsumeTheSameInvitation() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> tryActivation(start, userID, message));
			Future<Boolean> second = workers.submit(() -> tryActivation(start, userID, message));
			assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
		}

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
	}

	private boolean tryActivation(CyclicBarrier start, UUID userID, SentInvitation message) throws Exception {
		start.await(5, TimeUnit.SECONDS);

		try {
			activate(userID, message, "verified-proof");
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		}

	}

	private void assertDisabledWithoutAccess(UUID userID) {
		assertEquals(UserAccountStatus.DISABLED, account(userID).getStatu());
		assertTrue(account(userID).getRoles().isEmpty());
		assertTrue(membershipStore.findByUser(userID).isEmpty());
		assertTrue(authorizationService.hasTenantMembership(userID, tenantID));
		assertTrue(invitationStore.findByAccount(userID).isEmpty());
	}

	private void allowDelivery() {
		doAnswer(call -> {
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			return true;
		}).when(sender).send(anyString(), any(), anyString());
	}

	private void allowActivation(UUID userID, SentInvitation message) {
		when(verifier.verify(message.invitationID(), userID, account(userID).getPhoneNumber(), "verified-proof"))
				.thenReturn(true);
		when(credentialService.createCredential(userID, "valid-test-password"))
				.thenReturn(new UserCredential(userID, "new-hash"));
	}

	private void activate(UUID userID, SentInvitation message, String proof) {
		invitations.completeActivation(message.invitationID(), message.token(), proof, "activated_" + userID,
				"valid-test-password");
	}

	private SentInvitation sendInitial(UUID userID) {
		assertTrue(invitations.sendInitialInvitation(userID, structureID).sent());
		return sent.getLast();
	}

	private UUID preparePendingAccount() {
		UserAccount pending = createAccount(UserAccountStatus.PENDING_ACTIVATION, Set.of(), structureID);
		joinTenant(pending.getUserID());
		accessService.addStructureAccess(pending.getUserID(), structureID, Set.of(UserRole.EMPLOYEE));
		return pending.getUserID();
	}

	private UserAccount createAccount(UserAccountStatus status, Set<UserRole> roles, UUID employeeStructureID) {
		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Exemple", "Test", 35, userID + "@example.invalid",
				"0600000000", null, employeeStructureID);
		employees.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = status == UserAccountStatus.PENDING_ACTIVATION
				? UserAccount.pending(userID, employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(),
						employee.getEmail(), employee.getPhoneNumber())
				: new UserAccount(userID, "test_" + userID, status, roles, employee.getEmployeeID(),
						employee.getLastName(), employee.getFirstName(), employee.getEmail(),
						employee.getPhoneNumber());
		accountStore.createAccount(account);
		return account;
	}

	private UserAccount account(UUID userID) {
		return accountStore.findById(userID).orElseThrow();
	}

	private void joinTenant(UUID userID) {
		jdbcTemplate.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);
	}

	private UUID createStructure() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO organizational_structures (structure_id, structure_name, tenant_id) VALUES (?, ?, ?)",
				id, "Structure test", tenantID);
		return id;
	}

	private void authenticate(UserAccount principal) {
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	private record SentInvitation(String email, UUID invitationID, String token, Instant deliveredAt) {
	}
}
