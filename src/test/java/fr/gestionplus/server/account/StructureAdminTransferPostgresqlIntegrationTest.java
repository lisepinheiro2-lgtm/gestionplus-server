
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
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
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.PrivilegedConfirmationProofStore.Proof;
import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.account.StructureAdminTransferService.TransferPreview;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditFallback;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.DistributedSecurityUnavailableException;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.RequestRateLimitExceededException;
import fr.gestionplus.server.auth.SessionActivityStore;
import fr.gestionplus.server.auth.SessionIDHasher;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.TenantMembership;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;
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
	private SuccessionScope successionScope;
	@Autowired
	private SuccessionSecurity successionSecurity;
	@Autowired
	private SuccessionTransfer successionTransfer;
	@Autowired
	private SuccessionRevisionStore successionRevisions;
	@Autowired
	private SuccessionConsentStore successionConsents;
	@Autowired
	private SessionRegistry sessionRegistry;

	@MockitoBean
	private PrivilegedConfirmationVerifier verifier;
	@MockitoBean
	private CurrentSessionProvider sessionProvider;
	@MockitoSpyBean
	private SessionActivityStore sessionActivity;
	@MockitoSpyBean
	private AccountMfaService mfa;
	@Autowired
	private FindByIndexNameSessionRepository<? extends Session> springSessions;
	@Autowired
	private SuccessionConfirmationStore confirmationStore;
	@Autowired
	private TenantOrganizationStore tenantStore;
	private final ThreadLocal<String> currentSessionHash = new ThreadLocal<>();

	@Autowired
	private AuditStore auditStore;

	@MockitoSpyBean
	private AuditFallback auditFallback;
	@Autowired
	private PrivilegedConfirmationProofStore proofStore;
	@Autowired
	private PlatformTransactionManager transactionManager;
	@Autowired
	private Clock clock;
	@Autowired
	private SuccessionOfferStore offerStore;

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
		when(sessionProvider.getCurrentSessionIDHash()).thenAnswer(call -> currentSessionHash.get());
		when(sessionProvider.getActiveSessionIDHash()).thenAnswer(call -> currentSessionHash.get());
		when(verifier.consumeRecentProof(any(), anyString(), anyLong(), any(), anyString(), eq("verified-proof")))
				.thenReturn(true);
	}

	@AfterEach
	void clearSecurityContext() {
		currentSessionHash.remove();
		SecurityContextHolder.clearContext();
	}

	@Test
	void creatingASuccessionOfferPersistsTheProposalWithoutChangingRightsOrSessions() {
		UserAccount outgoing = accountStore.findById(actorID).orElseThrow();
		UserAccount successor = accountStore.findById(newAdminUserID).orElseThrow();
		String outgoingSession = registerSession(outgoing);
		String successorSession = registerSession(successor);
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());

		SuccessionOffer offer = createOffer(preview, null);

		SuccessionOffer stored = offerStore.findById(offer.offerID()).orElseThrow();
		assertEquals(actorID, stored.outgoingUserID());
		assertEquals(newAdminUserID, stored.successorUserID());
		assertEquals(tenantID, stored.tenantID());
		assertEquals(preview.confirmationDigest(), revision(stored).previewDigest());
		assertEquals(SuccessionOffer.OfferStatus.PENDING, stored.status());
		assertEquals(offer.createdAt().truncatedTo(ChronoUnit.MICROS), stored.createdAt());
		assertEquals(Duration.ofHours(24), Duration.between(stored.createdAt(), stored.expiresAt()));
		assertTrue(stored.adminRolesToTransfer().isEmpty());
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(Set.of("ADMIN"), rolesInStructure(oldAdminUserID));
		assertTrue(rolesInStructure(newAdminUserID).isEmpty());
		assertEquals(outgoing.getSessionVersion(), accountStore.findById(actorID).orElseThrow().getSessionVersion());
		assertEquals(successor.getSessionVersion(),
				accountStore.findById(newAdminUserID).orElseThrow().getSessionVersion());
		assertFalse(sessionRegistry.getSessionInformation(outgoingSession).isExpired());
		assertFalse(sessionRegistry.getSessionInformation(successorSession).isExpired());
	}

	@Test
	void creatingASuccessionOfferPreservesTheConfirmedLocalAdminChoicesWithoutTransferringThem() {
		service.transferStructureAdmin(service.previewTransfer(structureID, actorID), "verified-proof");
		authenticate(actorID);
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of(structureID));

		SuccessionOffer offer = createOffer(preview, null);

		assertEquals(List.of(structureID), offerStore.findById(offer.offerID()).orElseThrow().adminRolesToTransfer());
		assertEquals(Set.of("ADMIN"), rolesInStructure(actorID));
		assertTrue(rolesInStructure(newAdminUserID).isEmpty());
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
	}

	@Test
	void aSecondLiveProposalIsRefusedByTheTransactionalStore() {
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());
		SuccessionOffer offer = createOffer(preview, null);

		assertThrows(AccessDeniedException.class, () -> createOffer(preview, null));

		assertEquals(SuccessionOffer.OfferStatus.PENDING, offerStore.findById(offer.offerID()).orElseThrow().status());
		assertEquals(1, successionOfferCount());
	}

	@Test
	void cancellationByTheOutgoingSuperAdminAllowsANewProposalAndPreservesTheOldOne() {
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());
		SuccessionOffer first = createOffer(preview, null);

		assertTrue(cancel(first));
		SuccessionOffer second = createOffer(preview, null);

		assertEquals(SuccessionOffer.OfferStatus.CANCELLED,
				offerStore.findById(first.offerID()).orElseThrow().status());
		assertEquals(SuccessionOffer.OfferStatus.PENDING, offerStore.findById(second.offerID()).orElseThrow().status());
		assertEquals(2, successionOfferCount());
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
	}

	@Test
	void rollingBackTheBusinessTransactionAlsoRollsBackItsNewOffer() {
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());

		assertThrows(IllegalStateException.class,
				() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
					createOffer(preview, null);
					throw new IllegalStateException("Échec métier fictif après création de l’offre.");
				}));

		assertEquals(0, successionOfferCount());
		createOffer(preview, null);
		assertEquals(1, successionOfferCount());
	}

	@Test
	void twoConcurrentTransactionsCannotBothCreateAnOpenSuccessionOffer() throws Exception {
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> attemptOfferCreation(preview, start));
			Future<Boolean> second = workers.submit(() -> attemptOfferCreation(preview, start));
			assertTrue(first.get(15, TimeUnit.SECONDS) ^ second.get(15, TimeUnit.SECONDS));
		}

		assertEquals(1, successionOfferCount());
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
	}

	private boolean attemptOfferCreation(SuccessionPreview preview, CyclicBarrier start) throws Exception {
		authenticate(actorID);

		try {
			start.await(5, TimeUnit.SECONDS);
			createOffer(preview, null);
			return true;
		} catch (AccessDeniedException denied) {
			return false;
		} finally {
			SecurityContextHolder.clearContext();
		}

	}

	private int successionOfferCount() {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM succession_offers WHERE tenant_id = ?", Integer.class,
				tenantID);
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
				SELECT recipient_user_id FROM account_deactivation_notifications
				WHERE structure_id = ? AND kind IN ('ADMIN_REMOVED', 'ADMIN_GRANTED')
				""", UUID.class, structureID)));
		assertEquals(Set.of(actorID), Set.copyOf(jdbcTemplate.queryForList("""
				SELECT actor_user_id FROM account_deactivation_notifications
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
	void aRealStoredProofIsConsumedByTheAdminTransferTransaction() {
		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);
		String token = storeProof(actorID, Action.STRUCTURE_ADMIN_TRANSFER, preview.confirmationDigest());
		useTheStoredVerifier();

		service.transferStructureAdmin(preview, token);

		assertEquals(Set.of("ADMIN"), rolesInStructure(newAdminUserID));
		assertEquals(0, proofCount(actorID));
		boolean replayAccepted = new TransactionTemplate(transactionManager)
				.execute(status -> proofStore.consumeIfValid(actorID,
						currentSessionHash.get(), preview.actorSessionVersion(), Action.STRUCTURE_ADMIN_TRANSFER,
						preview.confirmationDigest(),
						PrivilegedConfirmationService.digest(token), clock.instant()));
		assertFalse(replayAccepted);
	}

	@Test
	void anAdminTransferRollbackRestoresItsRealProofForAnotherConfirmedAttempt() {
		TransferPreview preview = service.previewTransfer(structureID, newAdminUserID);
		String token = storeProof(actorID, Action.STRUCTURE_ADMIN_TRANSFER, preview.confirmationDigest());
		useTheStoredVerifier();
		UserAccount outgoing = accountStore.findById(oldAdminUserID).orElseThrow();
		String session = registerSession(outgoing);
		IllegalStateException failure = new IllegalStateException("Échec d’audit fictif après consommation.");
		doThrow(failure).when(auditFallback)
				.writeInBusinessTransaction(argThat(event -> event.getAction() == AuditAction.STRUCTURE_ADMIN_TRANSFER
						&& event.getResult() == AuditResult.SUCCESS));

		assertSame(failure,
				assertThrows(IllegalStateException.class, () -> service.transferStructureAdmin(preview, token)));

		assertEquals(1, proofCount(actorID));
		assertEquals(Set.of("ADMIN"), rolesInStructure(oldAdminUserID));
		assertTrue(rolesInStructure(newAdminUserID).isEmpty());
		assertFalse(sessionRegistry.getSessionInformation(session).isExpired());
		doCallRealMethod().when(auditFallback).writeInBusinessTransaction(any());
		service.transferStructureAdmin(preview, token);
		assertEquals(Set.of("ADMIN"), rolesInStructure(newAdminUserID));
		assertEquals(0, proofCount(actorID));
		assertTrue(sessionRegistry.getSessionInformation(session).isExpired());
	}

	@Test
	void creationConsumesItsRealProofAndRejectsReuseOrAnotherSession() {
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, Set.of());
		String originalSession = SessionIDHasher.hash(newRedisSession(actorID));
		currentSessionHash.set(originalSession);
		String proof = storeProof(actorID, Action.SUPER_ADMIN_SUCCESSION_CREATE, preview.confirmationDigest());
		useTheStoredVerifier();

		currentSessionHash.set("f".repeat(64));
		assertThrows(AccessDeniedException.class, () -> createOffer(preview, proof));
		assertEquals(1, proofCount(actorID));
		currentSessionHash.set(originalSession);

		SuccessionOffer offer = createOffer(preview, proof);
		assertEquals(0, proofCount(actorID));
		assertTrue(cancel(offer));
		assertThrows(AccessDeniedException.class, () -> createOffer(preview, proof));
		assertEquals(1, successionOfferCount());
	}

	@Test
	void acceptanceAloneChangesNoRightsAndCannotFinalizeWithoutBothConfirmations() {
		SuccessionOffer offer = acceptOffer(Set.of());
		storeConfirmation(offer, newAdminUserID, newRedisSession(newAdminUserID));

		assertThrows(AccessDeniedException.class, () -> finalizeTransfer(offer));

		assertEquals(SuccessionOffer.OfferStatus.ACCEPTED, offerStore.findById(offer.offerID()).orElseThrow().status());
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(1, confirmationCount(offer));
	}

	@Test
	void confirmingThroughTheSecurityComponentBindsTheExactSessionAndReplacesOnlyThatParticipantsConfirmation() {
		SuccessionOffer offer = acceptOffer(Set.of());
		String password = "Fictitious-Succession-2026!";
		jdbcTemplate.update("INSERT INTO user_credentials (user_id, password_hash) VALUES (?, ?)",
				newAdminUserID, Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(password));
		doReturn(AccountMfaService.Verification.VERIFIED).when(mfa).verifyForLogin(any(), eq("000042"));
		doReturn(AccountMfaService.Verification.DENIED).when(mfa).verifyForLogin(any(), eq("000043"));
		String outgoingSession = newRedisSession(actorID);
		storeConfirmation(offer, actorID, outgoingSession);
		String firstSession = newRedisSession(newAdminUserID);
		currentSessionHash.set(SessionIDHasher.hash(firstSession));
		confirm(offer, password, "000042");
		String secondSession = newRedisSession(newAdminUserID);
		currentSessionHash.set(SessionIDHasher.hash(secondSession));
		assertThrows(AccessDeniedException.class,
				() -> confirm(offer, password, "000043"));
		assertThrows(AccessDeniedException.class, () -> finalizeTransfer(offer));
		confirm(offer, password, "000042");
		assertEquals(SessionIDHasher.hash(secondSession), confirmationStore.findByOfferIDAndUserID(
				offer.offerID(), 1, newAdminUserID).orElseThrow().sessionIDHash());
		assertEquals(SessionIDHasher.hash(outgoingSession), confirmationStore.findByOfferIDAndUserID(
				offer.offerID(), 1, actorID).orElseThrow().sessionIDHash());
		assertEquals(2, confirmationCount(offer));
	}

	@Test
	void creationAndParticipantConfirmationShareTheQuotaEvenWhenVerificationRollsBack() {
		SuccessionOffer offer = acceptOffer(Set.of());
		authenticate(actorID);
		currentSessionHash.set(SessionIDHasher.hash(newRedisSession(actorID)));
		var preview = revision(offer).preview();

		for (int attempt = 0; attempt < 3; attempt++) {
			assertThrows(AccessDeniedException.class,
					() -> succession.issueSuccessionCreationProof(preview, "wrong", "000042"));
		}
		for (int attempt = 0; attempt < 2; attempt++) {
			var submission = successionSecurity.beginSubmission();
			assertThrows(AccessDeniedException.class, () -> new TransactionTemplate(transactionManager)
					.executeWithoutResult(status -> {
						var locked = successionScope.lock(offer.tenantID(), offer.outgoingUserID(), offer.successorUserID());
						var current = offerStore.findByIdForUpdate(offer.offerID()).orElseThrow();
						successionSecurity.verifyConfirmation(submission, current, revision(offer), locked.outgoing(),
								"wrong", "000042");
					}));
		}

		assertTrue(assertThrows(RequestRateLimitExceededException.class,
				() -> succession.issueSuccessionCreationProof(preview, "wrong", "000042")).retryAfterSeconds() > 0);
		assertEquals(0, proofCount(actorID));
		assertEquals(0, confirmationCount(offer));
		assertEquals(2, successionConsents.findByRevision(offer.offerID(), 1).size());
	}

	private String storeProof(UUID userID, Action action, String previewDigest) {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> proofStore.replaceCurrent(new Proof(
				userID, currentSessionHash.get(), accountStore.findById(userID).orElseThrow().getSessionVersion(),
				action,
				previewDigest, PrivilegedConfirmationService.digest(token), clock.instant().plusSeconds(300))));
		return token;
	}

	private void useTheStoredVerifier() {
		StoredPrivilegedConfirmationVerifier storedVerifier = new StoredPrivilegedConfirmationVerifier(proofStore,
				clock);
		when(verifier.consumeRecentProof(any(), anyString(), anyLong(), any(), anyString(), anyString())).thenAnswer(
				call -> storedVerifier.consumeRecentProof(call.getArgument(0), call.getArgument(1), call.getArgument(2),
						call.getArgument(3), call.getArgument(4), call.getArgument(5)));
	}

	private int proofCount(UUID userID) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM privileged_confirmation_proofs WHERE user_id = ?",
				Integer.class, userID);
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

		ReadySuccession ready = prepareSuccession(Set.of(structureID));
		UserAccount outgoing = accountStore.findById(actorID).orElseThrow();
		UserAccount incoming = accountStore.findById(newAdminUserID).orElseThrow();
		String oldSession = registerSession(outgoing);
		String newSession = registerSession(incoming);

		finalizeTransfer(ready.offer());
		assertEquals(SuccessionOffer.OfferStatus.COMPLETED,
				offerStore.findById(ready.offer().offerID()).orElseThrow().status());
		assertEquals(0, confirmationCount(ready.offer()));

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
		assertEquals(Set.of(actorID, newAdminUserID), Set.copyOf(jdbcTemplate.queryForList("""
				SELECT recipient_user_id FROM account_deactivation_notifications
				WHERE structure_id = ? AND actor_user_id = ? AND kind IN ('ADMIN_REMOVED', 'ADMIN_GRANTED')
				""", UUID.class, structureID, newAdminUserID)));
		assertEquals(2, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM account_deactivation_notifications
				WHERE structure_id = ? AND actor_user_id = ? AND target_id = recipient_user_id
				""", Integer.class, structureID, newAdminUserID));
		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"""
								SELECT COUNT(*) FROM audit_events WHERE action = 'SUPER_ADMIN_SUCCESSION' AND actor_user_id = ? AND result = 'SUCCESS'
								""",
						Long.class, newAdminUserID));
	}

	@Test
	void refusesSuccessionWhenTheConfirmedSituationHasChanged() {
		ReadySuccession ready = prepareSuccession(Set.of());
		saveMembership(newAdminUserID, structureID, Set.of(UserRole.EMPLOYEE));
		assertThrows(AccessDeniedException.class, () -> finalizeTransfer(ready.offer()));
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
	}

	@Test
	void increasingTheOutgoingAccountsSecurityVersionInvalidatesItsConfirmation() {
		ReadySuccession ready = prepareSuccession(Set.of());
		jdbcTemplate.update("UPDATE user_accounts SET session_version = session_version + 1 WHERE user_id = ?",
				actorID);
		assertThrows(AccessDeniedException.class, () -> finalizeTransfer(ready.offer()));
		assertEquals(actorID, tenantStore.findById(tenantID).orElseThrow().getSuperAdminUserID());
		assertEquals(2, confirmationCount(ready.offer()));
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
	void twoConcurrentFinalizationsCannotCompleteTheSameSuccessionTwice() throws Exception {
		ReadySuccession ready = prepareSuccession(Set.of());
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> first = workers.submit(() -> attemptSuccession(ready, start));
			Future<Boolean> second = workers.submit(() -> attemptSuccession(ready, start));
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
		ReadySuccession ready = prepareSuccession(Set.of(structureID));
		UserAccount outgoing = accountStore.findById(actorID).orElseThrow();
		UserAccount successor = accountStore.findById(newAdminUserID).orElseThrow();
		String outgoingSession = registerSession(outgoing);
		String successorSession = registerSession(successor);
		IllegalStateException failure = new IllegalStateException("Échec d’audit de succession.");
		doThrow(failure).when(auditFallback)
				.writeInBusinessTransaction(argThat(event -> event.getAction() == AuditAction.SUPER_ADMIN_SUCCESSION
						&& event.getResult() == AuditResult.SUCCESS));

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> finalizeTransfer(ready.offer())));
		assertEquals(SuccessionOffer.OfferStatus.ACCEPTED,
				offerStore.findById(ready.offer().offerID()).orElseThrow().status());
		assertEquals(2, confirmationCount(ready.offer()));

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

	@Test
	void concurrentCancellationAndFinalizationHaveExactlyOneWinner() throws Exception {
		ReadySuccession ready = prepareSuccession(Set.of());
		CyclicBarrier start = new CyclicBarrier(2);

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> finalization = workers.submit(() -> attemptSuccession(ready, start));
			Future<Boolean> cancellation = workers.submit(() -> {
				authenticate(actorID);

				try {
					start.await(5, TimeUnit.SECONDS);
					return cancel(ready.offer());
				} catch (AccessDeniedException denied) {
					return false;
				} finally {
					clearSecurityContext();
				}

			});
			boolean finalized = finalization.get(15, TimeUnit.SECONDS);
			assertEquals(!finalized, cancellation.get(15, TimeUnit.SECONDS));
			assertEquals(finalized ? SuccessionOffer.OfferStatus.COMPLETED : SuccessionOffer.OfferStatus.CANCELLED,
					offerStore.findById(ready.offer().offerID()).orElseThrow().status());
			assertEquals(finalized ? newAdminUserID : actorID,
					tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		}

		assertEquals(0, confirmationCount(ready.offer()));
	}

	@Test
	void logoutImmediatelyBeforeTheJointRedisCheckPreventsTheTransfer() {
		ReadySuccession ready = prepareSuccession(Set.of());
		doAnswer(call -> {
			sessionActivity.end(ready.outgoingSession());
			return call.callRealMethod();
		}).when(sessionActivity).validateTogether(ready.outgoingSession(), ready.successorSession());

		assertThrows(AccessDeniedException.class, () -> finalizeTransfer(ready.offer()));
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(2, confirmationCount(ready.offer()));
	}

	@Test
	void logoutImmediatelyAfterTheJointRedisCheckStillAllowsTheAuthorizedTransfer() {
		ReadySuccession ready = prepareSuccession(Set.of());
		doAnswer(call -> {
			Object result = call.callRealMethod();
			sessionActivity.end(ready.outgoingSession());
			return result;
		}).when(sessionActivity).validateTogether(ready.outgoingSession(), ready.successorSession());

		finalizeTransfer(ready.offer());
		assertEquals(newAdminUserID,
				tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(0, confirmationCount(ready.offer()));
	}

	@Test
	void redisUnavailabilityAtTheJointCheckLeavesTheAcceptedOfferAndRightsUnchanged() {
		ReadySuccession ready = prepareSuccession(Set.of());
		doThrow(new DistributedSecurityUnavailableException()).when(sessionActivity)
				.validateTogether(ready.outgoingSession(), ready.successorSession());

		assertThrows(DistributedSecurityUnavailableException.class,
				() -> finalizeTransfer(ready.offer()));
		assertEquals(actorID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getSuperAdminUserID());
		assertEquals(SuccessionOffer.OfferStatus.ACCEPTED,
				offerStore.findById(ready.offer().offerID()).orElseThrow().status());
		assertEquals(2, confirmationCount(ready.offer()));
	}

	@Test
	void lockedOrganizationReadBypassesAnAlreadyLoadedJpaEntity() {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			assertEquals(actorID, tenantStore.findById(tenantID).orElseThrow().getSuperAdminUserID());
			jdbcTemplate.update("UPDATE tenant_organizations SET super_admin_user_id = ? WHERE tenant_id = ?",
					newAdminUserID, tenantID);
			assertEquals(newAdminUserID, tenantStore.findByIdForUpdate(tenantID).orElseThrow().getSuperAdminUserID());
			status.setRollbackOnly();
		});
	}

	@Test
	void confirmationStorageRejectsAnotherUserOrSummaryAndKeepsTheCurrentRows() {
		ReadySuccession ready = prepareSuccession(Set.of());
		SuccessionOffer offer = ready.offer();
		var current = confirmationStore.findByOfferIDAndUserID(offer.offerID(), 1, newAdminUserID).orElseThrow();
		var wrongUser = new SuccessionConfirmation(offer.offerID(), oldAdminUserID, 1, 0, current.sessionIDHash(),
				current.previewDigest(), current.confirmedAt(), current.expiresAt());
		var wrongDigest = new SuccessionConfirmation(offer.offerID(), newAdminUserID, 1, current.sessionVersion(),
				current.sessionIDHash(), "f".repeat(64), current.confirmedAt(), current.expiresAt());
		TransactionTemplate transaction = new TransactionTemplate(transactionManager);
		assertThrows(AccessDeniedException.class,
				() -> transaction.executeWithoutResult(status -> confirmationStore.replaceCurrent(wrongUser)));
		assertThrows(AccessDeniedException.class,
				() -> transaction.executeWithoutResult(status -> confirmationStore.replaceCurrent(wrongDigest)));
		assertEquals(current, confirmationStore.findByOfferIDAndUserID(offer.offerID(), 1, newAdminUserID).orElseThrow());
		assertEquals(2, confirmationCount(offer));
	}

	private SuccessionOffer createOffer(SuccessionPreview preview, String proof) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			var locked = successionScope.lock(preview.tenantID(), preview.outgoing().userID(), preview.successor().userID());
			if (proof != null) {
				successionSecurity.requireCreationProof(locked.outgoing(), preview, proof);
			}
			Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
			SuccessionOffer offer = new SuccessionOffer(UUID.randomUUID(), actorID, newAdminUserID, tenantID,
					preview.adminRolesToTransfer(), 1L, SuccessionOffer.OfferStatus.PENDING, now,
					now.plus(Duration.ofHours(24)), null, null);
			offerStore.create(offer, new SuccessionRevision(offer.offerID(), 1, preview, preview.confirmationDigest(), now));
			return offer;
		});
	}

	private SuccessionRevision revision(SuccessionOffer offer) {
		return successionRevisions.find(offer.offerID(), 1).orElseThrow();
	}

	private void confirm(SuccessionOffer offer, String password, String totp) {
		var submission = successionSecurity.beginSubmission();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			var locked = successionScope.lock(offer.tenantID(), offer.outgoingUserID(), offer.successorUserID());
			SuccessionOffer current = offerStore.findByIdForUpdate(offer.offerID()).orElseThrow();
			confirmationStore.replaceCurrent(successionSecurity.verifyConfirmation(submission, current,
					revision(offer), locked.successor(), password, totp));
		});
	}

	private boolean cancel(SuccessionOffer offer) {
		return new TransactionTemplate(transactionManager).execute(status -> {
			successionScope.lock(offer.tenantID(), offer.outgoingUserID(), offer.successorUserID());
			return offerStore.close(offer.offerID(), 1, SuccessionOffer.ClosureReason.CANCELLED_BY_OUTGOING, clock.instant());
		});
	}

	private void finalizeTransfer(SuccessionOffer offer) {
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			var locked = successionScope.lock(offer.tenantID(), offer.outgoingUserID(), offer.successorUserID());
			successionTransfer.transferConfirmed(locked, offer.offerID(), revision(offer));
		});
	}

	private SuccessionOffer acceptOffer(Set<UUID> structures) {
		authenticate(actorID);
		SuccessionPreview preview = succession.previewSuccession(tenantID, newAdminUserID, structures);
		SuccessionOffer offer = createOffer(preview, null);
		authenticate(newAdminUserID);
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
			Instant now = clock.instant();
			for (UUID participant : List.of(actorID, newAdminUserID)) {
				assertTrue(successionConsents.saveIfAbsent(new SuccessionConsent(offer.offerID(), participant, 1, now)));
			}
			assertTrue(offerStore.markAccepted(offer.offerID(), 1, now));
		});
		return offer;
	}

	private ReadySuccession prepareSuccession(Set<UUID> structures) {
		SuccessionOffer offer = acceptOffer(structures);
		String outgoingSession = newRedisSession(actorID);
		String successorSession = newRedisSession(newAdminUserID);
		storeConfirmation(offer, actorID, outgoingSession);
		storeConfirmation(offer, newAdminUserID, successorSession);
		currentSessionHash.set(SessionIDHasher.hash(successorSession));
		return new ReadySuccession(offer, outgoingSession, successorSession);
	}

	private void storeConfirmation(SuccessionOffer offer, UUID userID, String sessionID) {
		long sessionVersion = accountStore.findById(userID).orElseThrow().getSessionVersion();
		Instant now = clock.instant();
		new TransactionTemplate(transactionManager).executeWithoutResult(status -> confirmationStore.replaceCurrent(
				new SuccessionConfirmation(offer.offerID(), userID, 1, sessionVersion, SessionIDHasher.hash(sessionID),
						revision(offer).previewDigest(), now, now.plusSeconds(300))));
	}

	private int confirmationCount(SuccessionOffer offer) {
		return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM succession_confirmations WHERE offer_id = ?",
				Integer.class, offer.offerID());
	}

	private String newRedisSession(UUID userID) {
		return saveRedisSession(springSessions, accountStore.findById(userID).orElseThrow());
	}

	private <S extends Session> String saveRedisSession(FindByIndexNameSessionRepository<S> repository,
			UserAccount account) {
		S session = repository.createSession();
		session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY,
				new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(account, null, List.of())));
		repository.save(session);
		sessionActivity.start(session.getId(), true);
		return session.getId();
	}

	private record ReadySuccession(SuccessionOffer offer, String outgoingSession, String successorSession) {
	}

	private boolean attemptSuccession(ReadySuccession ready, CyclicBarrier start) throws Exception {
		authenticate(newAdminUserID);
		currentSessionHash.set(SessionIDHasher.hash(ready.successorSession()));

		try {
			start.await(5, TimeUnit.SECONDS);
			finalizeTransfer(ready.offer());
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
		currentSessionHash.set(SessionIDHasher.hash(userID.toString()));
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
				"test_" + userID + "@example.invalid", "+12025550100", null);

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
