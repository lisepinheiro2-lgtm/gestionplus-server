
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

		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Structure A");

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

		OrganizationalStructure structureA = new OrganizationalStructure(structureAID, "Structure A");
		OrganizationalStructure structureB = new OrganizationalStructure(structureBID, "Structure B");

		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureA));
		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureB));

		StructureMembership membershipA = new StructureMembership(userID, structureAID,
				Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));

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
		assertEquals(Set.of("ADMIN", "ACCOUNTANT"),
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
	}

}
