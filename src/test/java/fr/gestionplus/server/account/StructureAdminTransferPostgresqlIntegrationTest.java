
package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditEvent;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditStore;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
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

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_admin_transfer_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.jpa.open-in-view=false", "spring.flyway.enabled=true" })
class StructureAdminTransferPostgresqlIntegrationTest {

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

	@MockitoSpyBean
	private AuditStore auditStore;

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

		UserAccount actor = createAccount(actorID, UserRole.SUPER_ADMIN);
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
				new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN))));

		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
	}

	@AfterEach
	void clearSecurityContext() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void transfersAdminAndRecordsAudit() {

		service.transferStructureAdmin(structureID, newAdminUserID);

		assertEquals(0L, membershipCount(oldAdminUserID));
		assertTrue(rolesInStructure(oldAdminUserID).isEmpty());
		assertEquals(1L, membershipCount(newAdminUserID));
		assertEquals(Set.of("ADMIN"), rolesInStructure(newAdminUserID));
		assertEquals(1L, jdbcTemplate.queryForObject(
				"SELECT COUNT(*) FROM structure_membership_roles " + "WHERE structure_id = ? AND role = 'ADMIN'",
				Long.class, structureID));
		assertEquals("ACTIVE", jdbcTemplate.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?",
				String.class, oldAdminUserID));

		UUID auditID = jdbcTemplate.queryForObject(
				"SELECT event_id FROM audit_events " + "WHERE actor_user_id = ? AND action = ?", UUID.class, actorID,
				"STRUCTURE_ADMIN_TRANSFER");

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
				new StructureMembership(oldAdminUserID, structureID, Set.of(UserRole.ADMIN, UserRole.EMPLOYEE))));
		membershipRepository.saveAndFlush(new StructureMembershipEntity(
				new StructureMembership(newAdminUserID, structureID, Set.of(UserRole.R_H))));

		service.transferStructureAdmin(structureID, newAdminUserID);

		assertEquals(Set.of("EMPLOYEE"), rolesInStructure(oldAdminUserID));
		assertEquals(Set.of("ADMIN", "R_H"), rolesInStructure(newAdminUserID));
	}

	@Test
	void rollsBackTransferWhenAuditFails() {

		doThrow(new IllegalStateException("Échec d'audit simulé")).when(auditStore).save(any(AuditEvent.class));

		assertThrows(InvalidDataAccessApiUsageException.class,
				() -> service.transferStructureAdmin(structureID, newAdminUserID));
		assertEquals(1L, membershipCount(oldAdminUserID));
		assertEquals(Set.of("ADMIN"), rolesInStructure(oldAdminUserID));
		assertEquals(0L, membershipCount(newAdminUserID));
		assertTrue(rolesInStructure(newAdminUserID).isEmpty());
		assertEquals(0L,
				jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM audit_events " + "WHERE actor_user_id = ? AND action = ?", Long.class,
						actorID, "STRUCTURE_ADMIN_TRANSFER"));
	}

	private UserAccount createAccount(UUID userID, UserRole role) {

		Employee employee = new Employee(UUID.randomUUID(), "Test", "Utilisateur", 35,
				"test_" + userID + "@example.invalid", "0600000000", null);

		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "test_" + userID, UserAccountStatus.ACTIVE, Set.of(role),
				employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());

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
