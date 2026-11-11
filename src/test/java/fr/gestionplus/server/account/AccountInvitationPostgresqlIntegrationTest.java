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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_invitation_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true", "gestionplus.activation.expiry-enabled=false",
		"gestionplus.notifications.from=false" })
class AccountInvitationPostgresqlIntegrationTest extends AuditStorageIntegrationTest {

	@Autowired
	private AccountInvitationDelivery deliveryWorker;
	@MockitoSpyBean
	private AccountInvitationDeliveryStore deliveryStore;

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
	private Clock clock;

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
		when(clock.instant()).thenAnswer(invocation -> Instant.now());
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
		makePendingDeliveryDue(userID);
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

		AccountInvitationService.Delivery delivery = invitations.replaceInvitation(userID, structureID,
				"corrected@example.invalid");
		assertFalse(delivery.sent());
		assertTrue(delivery.queued());
		assertTrue(deliveryStore.findById(delivery.requestID()).isPresent());

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
								Map.of(preparedStructure, Set.of(UserRole.EMPLOYEE)), null);
					}).sent();
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

	@Test
	void aRollbackOfTheCallingTransactionCannotSendAnInvitation() {
		UUID userID = preparePendingAccount();
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			AccountInvitationService.Delivery delivery = invitations.sendInitialInvitation(userID, structureID);
			assertTrue(delivery.queued());
			assertFalse(delivery.sent());
			assertTrue(sent.isEmpty());
			transaction.setRollbackOnly();
		});

		assertTrue(sent.isEmpty());
		assertTrue(deliveryStore.findPending(userID).isEmpty());
		assertTrue(invitationStore.findByAccount(userID).isEmpty());
		assertEquals(0, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM account_invitation_deliveries WHERE user_id = ?
				""", Integer.class, userID));
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void commitsTheDeliveryRequestBeforeAnyEmailAndKeepsTheSameTokenAfterARollback(boolean replacement) {
		UUID userID = preparePendingAccount();

		if (replacement) {
			sendInitial(userID);
			sent.clear();
		}

		doAnswer(call -> {
			assertEquals(1, jdbcTemplate.queryForObject("""
					SELECT COUNT(*) FROM account_invitation_deliveries WHERE user_id = ? AND state = 'PENDING'
					""", Integer.class, userID));
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			return true;
		}).when(sender).send(anyString(), any(), anyString());
		doThrow(new IllegalStateException("Échec simulé après acceptation SMTP"))
				.doCallRealMethod().when(deliveryStore).complete(any());

		AccountInvitationService.Delivery pending = replacement
				? invitations.replaceInvitation(userID, structureID, "corrected@example.invalid")
				: invitations.sendInitialInvitation(userID, structureID);

		assertTrue(pending.queued());
		assertEquals(1, sent.size());
		SentInvitation accepted = sent.getFirst();
		assertTrue(deliveryStore.findById(pending.requestID()).isPresent());

		if (replacement) {
			assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		} else {
			assertTrue(invitationStore.findByAccount(userID).isEmpty());
		}

		assertThrows(InvitationDeliveryPendingException.class,
				() -> activate(userID, accepted, "verified-proof"));
		SentInvitation forged = new SentInvitation(accepted.email(), accepted.invitationID(),
				"x".repeat(43), accepted.deliveredAt());
		assertThrows(AccessDeniedException.class, () -> activate(userID, forged, "verified-proof"));

		makePendingDeliveryDue(userID);
		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
		SentInvitation retried = sent.getLast();
		assertEquals(accepted.token(), retried.token());
		assertEquals(accepted.invitationID(), retried.invitationID());
		assertTrue(deliveryStore.findById(pending.requestID()).isEmpty());
		assertEquals(accepted.invitationID(), invitationStore.findByAccount(userID).orElseThrow().invitationID());
		allowActivation(userID, retried);
		activate(userID, retried, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
	}

	@Test
	void failedDeliveryIsRetainedAndCanBeRetriedWithoutTheOriginalSession() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		assertTrue(pending.queued());
		assertEquals(1, jdbcTemplate.queryForObject("""
				SELECT attempts FROM account_invitation_deliveries WHERE request_id = ?
				""", Integer.class, pending.requestID()));
		allowDelivery();
		SecurityContextHolder.clearContext();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));

		assertEquals(1, sent.size());
		assertEquals(actor.getUserID(), jdbcTemplate.queryForObject("""
				SELECT actor_user_id FROM audit_events event JOIN audit_targets target
				ON event.event_id = target.event_id
				WHERE action = 'INVITATION_CREATE' AND result = 'SUCCESS' AND target.target_id = ?
				""", UUID.class, sent.getFirst().invitationID()));
	}

	@Test
	void pendingDeliveryCannotSurviveARevokedActorOrDisabledTarget() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		new TransactionTemplate(transactionManager).executeWithoutResult(
				transaction -> accountStore.advanceSessionVersion(actor.getUserID()));
		allowDelivery();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());

		authenticate(account(actor.getUserID()));
		AccountInvitationService.Delivery replacement = invitations.sendInitialInvitation(userID, structureID);
		assertTrue(replacement.sent());
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery resend = invitations.resendInvitation(userID, structureID);
		invitations.cancelInvitation(userID, structureID);
		int delivered = sent.size();
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(resend.requestID()));
		assertEquals(delivered, sent.size());
		assertDisabledWithoutAccess(userID);
	}

	@Test
	void cancelsAnInitialQueuedInvitationBeforeItHasAnyUsableLink() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);

		invitations.cancelInvitation(userID, structureID);
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertDisabledWithoutAccess(userID);
	}

	@Test
	void cancelsAQueuedReintegrationWithoutRestoringAnyPreparedRoles() {
		UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(), structureID);
		joinTenant(former.getUserID());
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.inviteForReintegration(former.getUserID(),
				structureID, Map.of(structureID, Set.of(UserRole.TEAM_LEAD)), null);

		invitations.cancelInvitation(former.getUserID(), structureID);
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertDisabledWithoutAccess(former.getUserID());
	}

	@Test
	void rechecksCurrentActorPermissionsEvenWhenTheSessionVersionHasNotChanged() {
		UUID userID = preparePendingAccount();
		UserAccount secretary = createAccount(UserAccountStatus.ACTIVE, Set.of(), structureID);
		joinTenant(secretary.getUserID());
		accessService.addStructureAccess(secretary.getUserID(), structureID, Set.of(UserRole.SECRETARY));
		UserAccount principal = account(secretary.getUserID());
		authenticate(principal);
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		authenticate(actor);
		membershipStore.save(new StructureMembership(secretary.getUserID(), structureID, Set.of(UserRole.EMPLOYEE)));
		assertEquals(principal.getSessionVersion(), account(secretary.getUserID()).getSessionVersion());
		allowDelivery();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.CANCELLED, deliveryWorker.deliver(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
	}

	@Test
	void twoWorkersCannotSendTheSamePendingRequestConcurrently() throws Exception {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		allowDelivery();
		makePendingDeliveryDue(userID);
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<AccountInvitationDelivery.Status> first = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return deliveryWorker.deliver(pending.requestID());
			});
			Future<AccountInvitationDelivery.Status> second = workers.submit(() -> {
				start.await(5, TimeUnit.SECONDS);
				return deliveryWorker.deliver(pending.requestID());
			});
			AccountInvitationDelivery.Status firstResult = first.get(15, TimeUnit.SECONDS);
			AccountInvitationDelivery.Status secondResult = second.get(15, TimeUnit.SECONDS);
			assertTrue(firstResult == AccountInvitationDelivery.Status.SENT
					|| secondResult == AccountInvitationDelivery.Status.SENT);
			assertTrue(Set.of(AccountInvitationDelivery.Status.SENT, AccountInvitationDelivery.Status.PENDING)
					.contains(firstResult));
			assertTrue(Set.of(AccountInvitationDelivery.Status.SENT, AccountInvitationDelivery.Status.PENDING)
					.contains(secondResult));
		}

		assertEquals(1, sent.size());
		assertTrue(deliveryStore.wasSent(pending.requestID()));
	}

	@Test
	void smtpRunsWithoutATransactionOrBusinessLocks() {
		UUID userID = preparePendingAccount();
		doAnswer(call -> {
			assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
			new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
				jdbcTemplate.queryForObject("""
						SELECT structure_id FROM organizational_structures WHERE structure_id = ? FOR UPDATE NOWAIT
						""", UUID.class, structureID);
				jdbcTemplate.queryForObject("""
						SELECT user_id FROM user_accounts WHERE user_id = ? FOR UPDATE NOWAIT
						""", UUID.class, userID);
				jdbcTemplate.queryForObject("""
						SELECT user_id FROM user_accounts WHERE user_id = ? FOR UPDATE NOWAIT
						""", UUID.class, actor.getUserID());
			});
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		assertTrue(invitations.sendInitialInvitation(userID, structureID).sent());
		assertTrue(invitationStore.findByAccount(userID).isPresent());
	}

	@Test
	void aReservationOrRetryDelayPreventsImmediateRepeatedSmtpAttempts() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		allowDelivery();

		assertEquals(AccountInvitationDelivery.Status.PENDING, deliveryWorker.deliver(pending.requestID()));
		assertFalse(deliveryStore.findDue().contains(pending.requestID()));
		assertTrue(sent.isEmpty());
		assertEquals(1, deliveryAttempts(pending.requestID()));

		makePendingDeliveryDue(userID);
		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
		assertEquals(2, deliveryAttempts(pending.requestID()));
	}

	@Test
	void cancellationCanCommitWhileSmtpIsBlockedAndPreventsActivation() throws Exception {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		makePendingDeliveryDue(userID);
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch releaseSmtp = new CountDownLatch(1);
		doAnswer(call -> {
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			sending.countDown();
			assertTrue(releaseSmtp.await(10, TimeUnit.SECONDS));
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<AccountInvitationDelivery.Status> sendingResult = workers.submit(
					() -> deliveryWorker.deliver(pending.requestID()));

			try {
				assertTrue(sending.await(5, TimeUnit.SECONDS));
				Future<?> cancellation = workers.submit(() -> {
					authenticate(actor);

					try {
						withLockTimeout(() -> invitations.cancelInvitation(userID, structureID));
					} finally {
						SecurityContextHolder.clearContext();
					}

				});
				cancellation.get(5, TimeUnit.SECONDS);
			} finally {
				releaseSmtp.countDown();
			}

			assertEquals(AccountInvitationDelivery.Status.CANCELLED, sendingResult.get(10, TimeUnit.SECONDS));
		}

		assertDisabledWithoutAccess(userID);
		assertThrows(AccessDeniedException.class, () -> activate(userID, sent.getFirst(), "verified-proof"));
		assertEquals(0, successfulAudits(userID, "INVITATION_CREATE"));
	}

	@Test
	void aProcessInterruptionLeavesARecoverableReservationWithTheSameToken() {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		makePendingDeliveryDue(userID);
		doAnswer(call -> {
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			throw new AssertionError("Interruption du processus simulée");
		}).when(sender).send(anyString(), any(), anyString());

		assertThrows(AssertionError.class, () -> deliveryWorker.deliver(pending.requestID()));
		assertTrue(deliveryStore.findById(pending.requestID()).isPresent());
		assertTrue(invitationStore.findByAccount(userID).isEmpty());
		assertFalse(deliveryStore.findDue().contains(pending.requestID()));
		SentInvitation interrupted = sent.getFirst();
		allowDelivery();
		makePendingDeliveryDue(userID);

		assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
		assertEquals(interrupted.token(), sent.getLast().token());
		assertEquals(interrupted.invitationID(), sent.getLast().invitationID());
		assertEquals(1, successfulAudits(userID, "INVITATION_CREATE"));
	}

	@Test
	void anExpiredWorkerCannotOverwriteTheNewWorkersCompletion() throws Exception {
		UUID userID = preparePendingAccount();
		doReturn(false).when(sender).send(anyString(), any(), anyString());
		AccountInvitationService.Delivery pending = invitations.sendInitialInvitation(userID, structureID);
		makePendingDeliveryDue(userID);
		AtomicInteger sends = new AtomicInteger();
		AtomicReference<String> firstToken = new AtomicReference<>();
		CountDownLatch sending = new CountDownLatch(1);
		CountDownLatch releaseSmtp = new CountDownLatch(1);
		doAnswer(call -> {

			if (sends.incrementAndGet() == 1) {
				firstToken.set(call.getArgument(2));
				sending.countDown();
				assertTrue(releaseSmtp.await(10, TimeUnit.SECONDS));
				return false;
			}

			assertEquals(firstToken.get(), call.getArgument(2));
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		try (ExecutorService workers = Executors.newSingleThreadExecutor()) {
			Future<AccountInvitationDelivery.Status> oldWorker = workers.submit(
					() -> deliveryWorker.deliver(pending.requestID()));

			try {
				assertTrue(sending.await(5, TimeUnit.SECONDS));
				assertEquals(AccountInvitationDelivery.Status.PENDING, deliveryWorker.deliver(pending.requestID()));
				assertEquals(1, sends.get());
				makePendingDeliveryDue(userID);
				assertEquals(AccountInvitationDelivery.Status.SENT, deliveryWorker.deliver(pending.requestID()));
			} finally {
				releaseSmtp.countDown();
			}

			assertEquals(AccountInvitationDelivery.Status.SENT, oldWorker.get(10, TimeUnit.SECONDS));
		}

		assertEquals(2, sends.get());
		assertEquals(3, deliveryAttempts(pending.requestID()));
		assertTrue(deliveryStore.wasSent(pending.requestID()));
		assertEquals(1, successfulAudits(userID, "INVITATION_CREATE"));
	}

	private void makePendingDeliveryDue(UUID userID) {
		jdbcTemplate.update("""
				UPDATE account_invitation_deliveries SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
				WHERE user_id = ? AND state = 'PENDING'
				""", userID);
	}

	private int deliveryAttempts(UUID requestID) {
		return jdbcTemplate.queryForObject("""
				SELECT attempts FROM account_invitation_deliveries WHERE request_id = ?
				""", Integer.class, requestID);
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void aUsernameCollisionRollsBackActivationWithoutConsumingTheInvitation(boolean reintegration) {
		UUID userID;
		SentInvitation message;

		if (reintegration) {
			UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(), structureID);
			userID = former.getUserID();
			joinTenant(userID);
			credentials.saveAndFlush(new UserCredentialEntity(new UserCredential(userID, "old-test-hash")));
			assertTrue(invitations.inviteForReintegration(userID, structureID,
					Map.of(structureID, Set.of(UserRole.EMPLOYEE)), null).sent());
			message = sent.getLast();
		} else {
			userID = preparePendingAccount();
			message = sendInitial(userID);
		}

		allowActivation(userID, message);
		UserAccount other = createAccount(UserAccountStatus.ACTIVE, Set.of(), null);
		Invitation before = invitationStore.findByAccount(userID).orElseThrow();
		long version = account(userID).getSessionVersion();
		SecurityContextHolder.clearContext();

		AccountActivationConflictException failure = assertThrows(AccountActivationConflictException.class,
				() -> invitations.completeActivation(message.invitationID(), message.token(), "verified-proof",
						" " + other.getUserName() + " ", "valid-test-password"));

		assertFalse(failure.getMessage().contains(other.getUserName()));
		assertFalse(failure.getMessage().contains(other.getUserID().toString()));
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());
		assertEquals(version, account(userID).getSessionVersion());
		assertEquals(before, invitationStore.findByAccount(userID).orElseThrow());

		if (reintegration) {
			assertEquals("old-test-hash", credentials.findById(userID).orElseThrow().toDomain().getPasswordHash());
		} else {
			assertFalse(credentials.existsById(userID));
		}

		assertEquals(1, membershipStore.findByUser(userID).size());
		assertEquals(0, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(0, successfulAudits(userID, "INVITATION_TOKEN_USE"));

		activate(userID, message, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertEquals(UserAccountStatus.ACTIVE, account(other.getUserID()).getStatu());
	}

	@Test
	void twoConcurrentActivationsCannotClaimTheSameUsername() throws Exception {
		UUID firstID = preparePendingAccount();
		UUID secondID = preparePendingAccount();
		SentInvitation firstMessage = sendInitial(firstID);
		SentInvitation secondMessage = sendInitial(secondID);
		allowActivation(firstID, firstMessage);
		allowActivation(secondID, secondMessage);
		String userName = "shared_" + UUID.randomUUID();
		CyclicBarrier start = new CyclicBarrier(2);
		boolean firstWon;
		boolean secondWon;

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> claimUsername(start, firstMessage, userName));
			Future<Boolean> second = workers.submit(() -> claimUsername(start, secondMessage, userName));
			firstWon = first.get(15, TimeUnit.SECONDS);
			secondWon = second.get(15, TimeUnit.SECONDS);
		}

		assertTrue(firstWon ^ secondWon);
		UUID winner = firstWon ? firstID : secondID;
		UUID loser = firstWon ? secondID : firstID;
		SentInvitation losingMessage = firstWon ? secondMessage : firstMessage;
		assertEquals(UserAccountStatus.ACTIVE, account(winner).getStatu());
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(loser).getStatu());
		assertTrue(credentials.existsById(winner));
		assertFalse(credentials.existsById(loser));
		assertEquals(1, successfulAudits(winner, "ACCOUNT_ACTIVATE"));
		assertEquals(0, successfulAudits(loser, "ACCOUNT_ACTIVATE"));
		assertTrue(invitationStore.findByAccount(loser).orElseThrow().tokenDigest() != null);

		activate(loser, losingMessage, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(loser).getStatu());
	}

	@Test
	void anActivationCompletedBeforeTheDeadlineSurvivesAnExpirationWaitingForItsCommit() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		Instant deadline = invitationStore.findByAccount(userID).orElseThrow().expiresAt();
		AtomicReference<Instant> now = controlTime(deadline.minusSeconds(1));
		SecurityContextHolder.clearContext();

		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			Future<?> expiration = new TransactionTemplate(transactionManager).execute(transaction -> {
				accountStore.findByIdForUpdate(userID).orElseThrow();
				activate(userID, message, "verified-proof");
				now.set(deadline);
				int pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				Future<?> pending = worker.submit(() -> withLockTimeout(
						() -> invitations.expireInvitation(message.invitationID())));
				awaitBlockedBy(pid, pending);
				return pending;
			});
			expiration.get(15, TimeUnit.SECONDS);
		}

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertTrue(credentials.existsById(userID));
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		assertEquals(1, membershipStore.findByUser(userID).size());
		assertEquals(1, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(0, successfulAudits(userID, "INVITATION_EXPIRE"));
	}

	@Test
	void anExpirationHoldingTheAccountLockPreventsAConcurrentActivation() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		controlTime(invitationStore.findByAccount(userID).orElseThrow().expiresAt());
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, account(userID));
		SecurityContextHolder.clearContext();

		try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
			Future<Boolean> activation = new TransactionTemplate(transactionManager).execute(transaction -> {
				invitations.expireInvitation(message.invitationID());
				int pid = jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class);
				Future<Boolean> pending = worker.submit(() -> activateIfAllowed(userID, message));
				awaitBlockedBy(pid, pending);
				return pending;
			});
			assertFalse(activation.get(15, TimeUnit.SECONDS));
		}

		assertDisabledWithoutAccess(userID);
		assertFalse(credentials.existsById(userID));
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(0, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(1, successfulAudits(userID, "INVITATION_EXPIRE"));
	}

	@Test
	void crossingTheDeadlineDuringIdentityVerificationLetsExpirationWin() throws Exception {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		Instant deadline = invitationStore.findByAccount(userID).orElseThrow().expiresAt();
		AtomicReference<Instant> now = controlTime(deadline.minusSeconds(1));
		AtomicInteger pid = new AtomicInteger();
		CountDownLatch verifying = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		doAnswer(invocation -> {
			pid.set(jdbcTemplate.queryForObject("SELECT pg_backend_pid()", Integer.class));
			verifying.countDown();
			assertTrue(release.await(5, TimeUnit.SECONDS));
			return true;
		}).when(verifier).verify(message.invitationID(), userID, account(userID).getPhoneNumber(), "verified-proof");
		SecurityContextHolder.clearContext();

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> activation = workers.submit(() -> activateIfAllowed(userID, message));

			try {
				assertTrue(verifying.await(5, TimeUnit.SECONDS));
				now.set(deadline);
				Future<?> expiration = workers.submit(() -> withLockTimeout(
						() -> invitations.expireInvitation(message.invitationID())));
				awaitBlockedBy(pid.get(), expiration);
				release.countDown();
				assertFalse(activation.get(15, TimeUnit.SECONDS));
				expiration.get(15, TimeUnit.SECONDS);
			} finally {
				release.countDown();
			}

		}

		assertDisabledWithoutAccess(userID);
		assertFalse(credentials.existsById(userID));
		assertEquals(0, successfulAudits(userID, "ACCOUNT_ACTIVATE"));
		assertEquals(1, successfulAudits(userID, "INVITATION_EXPIRE"));
	}

	private AtomicReference<Instant> controlTime(Instant initial) {
		AtomicReference<Instant> now = new AtomicReference<>(initial);
		when(clock.instant()).thenAnswer(invocation -> now.get());
		return now;
	}

	private boolean claimUsername(CyclicBarrier start, SentInvitation message, String userName) throws Exception {
		start.await(5, TimeUnit.SECONDS);

		try {
			withLockTimeout(() -> invitations.completeActivation(message.invitationID(), message.token(),
					"verified-proof", userName, "valid-test-password"));
			return true;
		} catch (AccountActivationConflictException conflict) {
			return false;
		}

	}

	private boolean activateIfAllowed(UUID userID, SentInvitation message) {

		try {
			withLockTimeout(() -> activate(userID, message, "verified-proof"));
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		}

	}

	private void withLockTimeout(Runnable operation) {
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
			operation.run();
		});
	}

	private void awaitBlockedBy(int pid, Future<?> waiting) {
		Instant deadline = Instant.now().plusSeconds(3);

		while (!waiting.isDone() && Instant.now().isBefore(deadline)) {
			boolean blocked = Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
					SELECT EXISTS (
					    SELECT 1 FROM pg_locks WHERE NOT granted AND ? = ANY(pg_blocking_pids(pid))
					)
					""", Boolean.class, pid));

			if (blocked) {
				return;
			}

			try {
				Thread.sleep(20);
			} catch (InterruptedException interrupted) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(interrupted);
			}

		}

		throw new AssertionError("L’opération concurrente doit attendre le verrou du compte.");
	}

	private int successfulAudits(UUID userID, String action) {
		return jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events event JOIN audit_targets target ON event.event_id = target.event_id
				WHERE event.action = ? AND event.result = 'SUCCESS'
				    AND target.target_type = 'ACCOUNT' AND target.target_id = ?
				""", Integer.class, action, userID);
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
