package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.employee.EmployeeResponsibilityService;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.persistence.EmployeeEntity;
import fr.gestionplus.server.persistence.EmployeeJpaRepository;
import fr.gestionplus.server.persistence.UserCredentialJpaRepository;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_invitation_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true", "gestionplus.activation.expiry-enabled=false",
		"gestionplus.notifications.from=false" })
abstract class AbstractAccountInvitationPostgresqlIntegrationTest extends AuditStorageIntegrationTest {

	@Autowired
	protected AccountInvitationDelivery deliveryWorker;
	@Autowired
	protected MailDeliveryExecutor deliveryExecutor;
	
	@MockitoSpyBean
	protected AccountInvitationDeliveryStore deliveryStore;

	@Autowired
	protected AccountInvitationService invitations;
	@Autowired
	protected AccountActivationService activation;
	@Autowired
	protected AccountInvitationStore invitationStore;
	@Autowired
	protected AccountStore accountStore;
	@Autowired
	protected StructureAccessService accessService;
	@Autowired
	protected StructureMembershipStore membershipStore;
	@Autowired
	protected AuthorizationService authorizationService;
	@Autowired
	protected EmployeeResponsibilityService responsibilityService;
	@Autowired
	protected EmployeeJpaRepository employees;
	@Autowired
	protected UserCredentialJpaRepository credentials;
	@Autowired
	protected SessionRegistry sessionRegistry;
	@Autowired
	protected JdbcTemplate jdbcTemplate;
	@Autowired
	protected PlatformTransactionManager transactionManager;

	@MockitoBean
	protected Clock clock;
	@MockitoBean
	protected AccountInvitationSender sender;
	@MockitoBean
	protected ActivationIdentityVerifier verifier;
	@MockitoBean
	protected CredentialService credentialService;

	protected final List<SentInvitation> sent = new ArrayList<>();

	protected UUID tenantID;

	protected UUID structureID;

	protected UserAccount actor;

	@BeforeEach
	void setUp() {
		jdbcTemplate.update("DELETE FROM account_invitation_deliveries");
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
		jdbcTemplate.update("DELETE FROM account_invitation_deliveries");
	}

	protected void makePendingDeliveryDue(UUID userID) {
		jdbcTemplate.update("""
				UPDATE account_invitation_deliveries SET next_attempt_at = CURRENT_TIMESTAMP - INTERVAL '1 second'
				WHERE user_id = ? AND state = 'PENDING'
				""", userID);
	}

	protected AccountInvitationService.Delivery awaitDelivered(AccountInvitationService.Delivery delivery) {
		long deadline = System.currentTimeMillis() + 5000;

		while (!delivery.sent()) {

			if (System.currentTimeMillis() > deadline) {
				fail("envoi jamais confirmé");
			}

			try {
				Thread.sleep(10);
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(failure);
			}

		}

		return delivery;
	}

	protected void withLockTimeout(Runnable operation) {
		new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
			jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
			operation.run();
		});
	}

	protected int successfulAudits(UUID userID, String action) {
		return jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events event JOIN audit_targets target ON event.event_id = target.event_id
				WHERE event.action = ? AND event.result = 'SUCCESS'
				    AND target.target_type = 'ACCOUNT' AND target.target_id = ?
				""", Integer.class, action, userID);
	}

	protected void assertDisabledWithoutAccess(UUID userID) {
		assertEquals(UserAccountStatus.DISABLED, account(userID).getStatu());
		assertTrue(account(userID).getRoles().isEmpty());
		assertTrue(membershipStore.findByUser(userID).isEmpty());
		assertTrue(authorizationService.hasTenantMembership(userID, tenantID));
		assertTrue(invitationStore.findByAccount(userID).isEmpty());
	}

	protected void allowDelivery() {
		doAnswer(call -> {
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			return true;
		}).when(sender).send(anyString(), any(), anyString());
	}

	protected void allowActivation(UUID userID, SentInvitation message) {
		when(verifier.verify(message.invitationID(), userID, account(userID).getPhoneNumber(), "verified-proof"))
				.thenReturn(true);
		when(credentialService.encodePassword(userID, "valid-test-password"))
				.thenReturn(new UserCredential(userID, "new-hash"));
	}

	protected void activate(UUID userID, SentInvitation message, String proof) {
		activation.completeActivation(message.invitationID(), message.token(), proof, "activated_" + userID,
				"valid-test-password");
	}

	protected SentInvitation sendInitial(UUID userID) {
		assertTrue(awaitDelivered(invitations.sendInitialInvitation(userID, structureID)).sent());
		return sent.getLast();
	}

	protected UUID preparePendingAccount() {
		UserAccount pending = createAccount(UserAccountStatus.PENDING_ACTIVATION, Set.of(), structureID);
		joinTenant(pending.getUserID());
		accessService.addStructureAccess(pending.getUserID(), structureID, Set.of(UserRole.EMPLOYEE));
		return pending.getUserID();
	}

	protected UserAccount createAccount(UserAccountStatus status, Set<UserRole> roles, UUID employeeStructureID) {
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

	protected UserAccount account(UUID userID) {
		return accountStore.findById(userID).orElseThrow();
	}

	protected void joinTenant(UUID userID) {
		jdbcTemplate.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);
	}

	protected UUID createStructure() {
		UUID id = UUID.randomUUID();
		jdbcTemplate.update(
				"INSERT INTO organizational_structures (structure_id, structure_name, tenant_id) VALUES (?, ?, ?)",
				id, "Structure test", tenantID);
		return id;
	}

	protected void authenticate(UserAccount principal) {
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(principal, null, List.of()));
	}

	protected record SentInvitation(String email, UUID invitationID, String token, Instant deliveredAt) {
	}

	protected void awaitFirstVerdict(UUID userID) {
		long deadline = System.currentTimeMillis() + 5000;

		while (firstVerdicts(userID) < 1) {

			if (System.currentTimeMillis() > deadline) {
				fail("premier cycle jamais terminé");
			}

			try {
				Thread.sleep(10);
			} catch (InterruptedException failure) {
				Thread.currentThread().interrupt();
				throw new IllegalStateException(failure);
			}

		}

	}

	private int firstVerdicts(UUID userID) {
		return jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events event JOIN audit_targets target
				ON event.event_id = target.event_id
				WHERE event.result IN ('FAILURE', 'DENIED')
				    AND target.target_type = 'ACCOUNT' AND target.target_id = ?
				""", Integer.class, userID);
	}
}
