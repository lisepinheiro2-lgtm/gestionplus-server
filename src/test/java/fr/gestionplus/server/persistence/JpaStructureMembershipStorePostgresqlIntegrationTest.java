
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_membership_store_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true" })
class JpaStructureMembershipStorePostgresqlIntegrationTest {

	@Autowired
	private StructureMembershipStore store;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;
	@Autowired
	private TenantOrganizationJpaRepository tenantRepository;
	@Autowired
	private OrganizationalStructureJpaRepository structureRepository;
	@Autowired
	private StructureMembershipJpaRepository membershipRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void findsMembershipWithRolesOutsideTransaction() {

		UUID userID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Alice", "Test", 35, "alice@example.invalid", "0600000000",
				null);

		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "alice_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Structure A", createTenant());

		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structure));

		StructureMembership membership = new StructureMembership(userID, structureID,
				Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));

		membershipRepository.saveAndFlush(new StructureMembershipEntity(membership));

		StructureMembership restored = store.findByUserAndStructure(userID, structureID).orElseThrow();

		assertEquals(userID, restored.getUserID());
		assertEquals(structureID, restored.getStructureID());
		assertEquals(membership.getRoles(), restored.getRoles());
	}

	@Test
	void returnsEmptyWhenMembershipDoesNotExist() {
		assertTrue(store.findByUserAndStructure(UUID.randomUUID(), UUID.randomUUID()).isEmpty());
	}

	@Test
	void deletesMembershipInOneStructureWithoutAffectingTheOther() {

		UUID userID = UUID.randomUUID();
		UUID structureAID = UUID.randomUUID();
		UUID structureBID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Alice", "Test", 35, "alice@example.invalid", "0600000000",
				null);

		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "alice_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		UUID tenantID = createTenant();
		OrganizationalStructure structureA = new OrganizationalStructure(structureAID, "Structure A", tenantID);
		OrganizationalStructure structureB = new OrganizationalStructure(structureBID, "Structure B", tenantID);

		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureA));
		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureB));

		StructureMembership membershipA = new StructureMembership(userID, structureAID,
				Set.of(UserRole.SECRETARY, UserRole.ACCOUNTANT));

		StructureMembership membershipB = new StructureMembership(userID, structureBID, Set.of(UserRole.EMPLOYEE));

		membershipRepository.saveAndFlush(new StructureMembershipEntity(membershipA));
		membershipRepository.saveAndFlush(new StructureMembershipEntity(membershipB));

		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM structure_memberships WHERE user_id = ? AND structure_id = ?", Long.class,
						userID, structureAID));
		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM structure_memberships WHERE user_id = ? AND structure_id = ?", Long.class,
						userID, structureBID));
		assertEquals(Set.of("SECRETARY", "ACCOUNTANT"),
				Set.copyOf(jdbcTemplate.queryForList(
						"SELECT role FROM structure_membership_roles WHERE user_id = ? AND structure_id = ?",
						String.class, userID, structureAID)));
		assertEquals(Set.of("EMPLOYEE"),
				Set.copyOf(jdbcTemplate.queryForList(
						"SELECT role FROM structure_membership_roles WHERE user_id = ? AND structure_id = ?",
						String.class, userID, structureBID)));

		assertEquals("ACTIVE", jdbcTemplate.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?",
				String.class, userID));

		store.deleteByUserAndStructure(userID, structureAID);

		assertEquals(0L,
				jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM structure_memberships WHERE user_id = ? AND structure_id = ?", Long.class,
						userID, structureAID));
		assertEquals(0L,
				jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM structure_membership_roles WHERE user_id = ? AND structure_id = ?",
						Long.class, userID, structureAID));

		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM structure_memberships WHERE user_id = ? AND structure_id = ?", Long.class,
						userID, structureBID));
		assertEquals(Set.of("EMPLOYEE"),
				Set.copyOf(jdbcTemplate.queryForList(
						"SELECT role FROM structure_membership_roles WHERE user_id = ? AND structure_id = ?",
						String.class, userID, structureBID)));

		assertEquals(1L, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_accounts WHERE user_id = ?", Long.class,
				userID));
		assertEquals("ACTIVE", jdbcTemplate.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?",
				String.class, userID));
		assertEquals(Set.of("EMPLOYEE"), Set.copyOf(jdbcTemplate
				.queryForList("SELECT role FROM user_account_roles WHERE user_id = ?", String.class, userID)));

		assertEquals(Set.of(structureAID, structureBID), Set.copyOf(jdbcTemplate.queryForList(
				"SELECT structure_id FROM organizational_structures WHERE tenant_id = ?", UUID.class, tenantID)));
		assertEquals(tenantID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getTenantID());
		StructureMembership restoredB = store.findByUserAndStructure(userID, structureBID).orElseThrow();
		assertEquals(membershipB.getRoles(), restoredB.getRoles());
		assertEquals(userID, restoredB.getUserID());
		assertTrue(store.findByUserAndStructure(userID, structureAID).isEmpty());
	}

	private UUID createTenant() {
		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Super", "Admin", 35, "super@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "super_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.SUPER_ADMIN), employee.getEmployeeID());
		accountRepository.saveAndFlush(new UserAccountEntity(account));

		UUID tenantID = UUID.randomUUID();
		tenantRepository.saveAndFlush(
				new TenantOrganizationEntity(new TenantOrganization(tenantID, "Entreprise", userID)));
		return tenantID;
	}
}
