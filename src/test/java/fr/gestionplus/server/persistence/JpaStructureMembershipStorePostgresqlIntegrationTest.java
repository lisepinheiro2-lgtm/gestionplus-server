
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
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
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(),
				employee.getEmail(), employee.getPhoneNumber());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		UUID tenantID = createTenant();
		joinTenant(userID, tenantID);
		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Structure A", tenantID);

		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structure));

		StructureMembership membership = new StructureMembership(userID, structureID,
				Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));

		membershipRepository.saveAndFlush(new StructureMembershipEntity(membership, tenantID));

		StructureMembership restored = store.findByUserAndStructure(userID, structureID).orElseThrow();

		assertEquals(userID, restored.getUserID());
		assertEquals(structureID, restored.getStructureID());
		assertEquals(membership.getRoles(), restored.getRoles());
		assertEquals(membership.getRoles(), store.findEffectiveLocalRoles(userID));
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
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(),
				employee.getEmail(), employee.getPhoneNumber());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		UUID tenantID = createTenant();
		joinTenant(userID, tenantID);
		OrganizationalStructure structureA = new OrganizationalStructure(structureAID, "Structure A", tenantID);
		OrganizationalStructure structureB = new OrganizationalStructure(structureBID, "Structure B", tenantID);

		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureA));
		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureB));

		StructureMembership membershipA = new StructureMembership(userID, structureAID,
				Set.of(UserRole.SECRETARY, UserRole.ACCOUNTANT));

		StructureMembership membershipB = new StructureMembership(userID, structureBID, Set.of(UserRole.EMPLOYEE));

		membershipRepository.saveAndFlush(new StructureMembershipEntity(membershipA, tenantID));
		membershipRepository.saveAndFlush(new StructureMembershipEntity(membershipB, tenantID));

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

		assertEquals(Set.of(structureAID, structureBID),
				Set.copyOf(jdbcTemplate.queryForList(
						"SELECT structure_id FROM organizational_structures WHERE tenant_id = ?", UUID.class,
						tenantID)));
		assertEquals(tenantID, tenantRepository.findById(tenantID).orElseThrow().toDomain().getTenantID());
		StructureMembership restoredB = store.findByUserAndStructure(userID, structureBID).orElseThrow();
		assertEquals(membershipB.getRoles(), restoredB.getRoles());
		assertEquals(userID, restoredB.getUserID());
		assertTrue(store.findByUserAndStructure(userID, structureAID).isEmpty());
	}

	private UUID createTenant() {

		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Super", "Admin", 35, "super@example.invalid", "0600000000",
				null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "super_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.SUPER_ADMIN), employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(),
				employee.getEmail(), employee.getPhoneNumber());
		accountRepository.saveAndFlush(new UserAccountEntity(account));

		UUID tenantID = UUID.randomUUID();
		tenantRepository
				.saveAndFlush(new TenantOrganizationEntity(new TenantOrganization(tenantID, "Entreprise", userID)));

		return tenantID;
	}

	private UUID createStandardAccount() {

		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Alice", "Test", 35, "alice_" + userID + "@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "alice_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(),
				employee.getEmail(), employee.getPhoneNumber());
		accountRepository.saveAndFlush(new UserAccountEntity(account));

		return userID;
	}

	private void joinTenant(UUID userID, UUID tenantID) {
		jdbcTemplate.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);
	}

	private void createStructure(UUID structureID, UUID tenantID) {

		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Structure test", tenantID);
		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structure));
	}

	private Set<String> rolesInDatabase(UUID userID, UUID structureID) {

		return Set.copyOf(jdbcTemplate.queryForList(
				"SELECT role FROM structure_membership_roles " + "WHERE user_id = ? AND structure_id = ?", String.class,
				userID, structureID));
	}

	@Test
	void savesNewMembership() {

		UUID userID = createStandardAccount();
		UUID structureID = UUID.randomUUID();
		UUID tenantID = createTenant();
		createStructure(structureID, tenantID);
		joinTenant(userID, tenantID);
		StructureMembership membership = new StructureMembership(userID, structureID, Set.of(UserRole.EMPLOYEE));

		store.save(membership);

		StructureMembership restored = store.findByUserAndStructure(userID, structureID).orElseThrow();

		assertEquals(membership.getRoles(), restored.getRoles());
		assertEquals(membership.getRoles(), store.findEffectiveLocalRoles(userID));
		assertEquals(Set.of("EMPLOYEE"), rolesInDatabase(userID, structureID));
	}

	@Test
	void replacesRolesWithoutAffectingAnotherStructure() {

		UUID userID = createStandardAccount();
		UUID tenantID = createTenant();
		UUID structureAID = UUID.randomUUID();
		UUID structureBID = UUID.randomUUID();

		createStructure(structureAID, tenantID);
		createStructure(structureBID, tenantID);
		joinTenant(userID, tenantID);

		store.save(new StructureMembership(userID, structureAID, Set.of(UserRole.R_H)));
		store.save(new StructureMembership(userID, structureBID, Set.of(UserRole.SECRETARY)));
		store.save(new StructureMembership(userID, structureAID, Set.of(UserRole.ADMIN)));

		assertEquals(Set.of("ADMIN"), rolesInDatabase(userID, structureAID));
		assertEquals(Set.of("SECRETARY"), rolesInDatabase(userID, structureBID));
		assertEquals(Set.of(UserRole.ADMIN),
				store.findByUserAndStructure(userID, structureAID).orElseThrow().getRoles());
	}

	@Test
	void rejectsSecondAdminInSameStructure() {

		UUID firstUserID = createStandardAccount();
		UUID secondUserID = createStandardAccount();
		UUID structureID = UUID.randomUUID();
		UUID tenantID = createTenant();
		createStructure(structureID, tenantID);
		joinTenant(firstUserID, tenantID);
		joinTenant(secondUserID, tenantID);

		store.save(new StructureMembership(firstUserID, structureID, Set.of(UserRole.ADMIN)));
		store.save(new StructureMembership(secondUserID, structureID, Set.of(UserRole.EMPLOYEE)));

		assertThrows(DataIntegrityViolationException.class,
				() -> store.save(new StructureMembership(secondUserID, structureID, Set.of(UserRole.ADMIN))));
		assertEquals(Set.of("ADMIN"), rolesInDatabase(firstUserID, structureID));
		assertEquals(Set.of("EMPLOYEE"), rolesInDatabase(secondUserID, structureID));
	}

	@Test
	void allowsSameAdminInMultipleStructures() {

		UUID userID = createStandardAccount();
		UUID tenantID = createTenant();
		UUID structureAID = UUID.randomUUID();
		UUID structureBID = UUID.randomUUID();

		createStructure(structureAID, tenantID);
		createStructure(structureBID, tenantID);
		joinTenant(userID, tenantID);

		store.save(new StructureMembership(userID, structureAID, Set.of(UserRole.ADMIN)));
		store.save(new StructureMembership(userID, structureBID, Set.of(UserRole.ADMIN)));

		assertEquals(Set.of("ADMIN"), rolesInDatabase(userID, structureAID));
		assertEquals(Set.of("ADMIN"), rolesInDatabase(userID, structureBID));
	}

	@Test
	void findsAdminOfStructure() {

		UUID structureID = UUID.randomUUID();
		UUID tenantID = createTenant();
		createStructure(structureID, tenantID);

		UUID adminID = createStandardAccount();
		UUID rhID = createStandardAccount();
		joinTenant(adminID, tenantID);
		joinTenant(rhID, tenantID);

		store.save(new StructureMembership(rhID, structureID, Set.of(UserRole.R_H)));
		store.save(new StructureMembership(adminID, structureID, Set.of(UserRole.ADMIN)));

		StructureMembership result = store.findAdminByStructure(structureID).orElseThrow();

		assertEquals(adminID, result.getUserID());
		assertEquals(structureID, result.getStructureID());
		assertEquals(Set.of(UserRole.ADMIN), result.getRoles());
	}

	@Test
	void returnsEmptyWhenStructureHasNoAdmin() {

		UUID structureID = UUID.randomUUID();
		UUID tenantID = createTenant();
		createStructure(structureID, tenantID);

		UUID userID = createStandardAccount();
		joinTenant(userID, tenantID);
		store.save(new StructureMembership(userID, structureID, Set.of(UserRole.R_H)));

		assertTrue(store.findAdminByStructure(structureID).isEmpty());
	}

	@Test
	void findsAdminOnlyInRequestedStructure() {

		UUID tenantID = createTenant();
		UUID structureAID = UUID.randomUUID();
		UUID structureBID = UUID.randomUUID();

		createStructure(structureAID, tenantID);
		createStructure(structureBID, tenantID);

		UUID adminAID = createStandardAccount();
		UUID adminBID = createStandardAccount();
		joinTenant(adminAID, tenantID);
		joinTenant(adminBID, tenantID);

		store.save(new StructureMembership(adminAID, structureAID, Set.of(UserRole.ADMIN)));
		store.save(new StructureMembership(adminBID, structureBID, Set.of(UserRole.ADMIN)));

		StructureMembership resultA = store.findAdminByStructure(structureAID).orElseThrow();
		StructureMembership resultB = store.findAdminByStructure(structureBID).orElseThrow();

		assertEquals(adminAID, resultA.getUserID());
		assertEquals(structureAID, resultA.getStructureID());
		assertEquals(adminBID, resultB.getUserID());
		assertEquals(structureBID, resultB.getStructureID());
	}

	@Test
	void findsOnlyAdminMembershipsOfUserInRequestedTenant() {

		UUID userID = createStandardAccount();
		UUID otherUserID = createStandardAccount();

		UUID tenantAID = createTenant();
		UUID tenantBID = createTenant();

		UUID structureAID = UUID.randomUUID();
		UUID structureBID = UUID.randomUUID();
		UUID structureCID = UUID.randomUUID();
		UUID structureDID = UUID.randomUUID();
		UUID structureEID = UUID.randomUUID();

		createStructure(structureAID, tenantAID);
		createStructure(structureBID, tenantAID);
		createStructure(structureCID, tenantBID);
		createStructure(structureDID, tenantAID);
		createStructure(structureEID, tenantAID);
		joinTenant(userID, tenantAID);
		joinTenant(userID, tenantBID);
		joinTenant(otherUserID, tenantAID);

		store.save(new StructureMembership(userID, structureAID, Set.of(UserRole.ADMIN)));
		store.save(new StructureMembership(userID, structureBID, Set.of(UserRole.ADMIN)));
		store.save(new StructureMembership(userID, structureCID, Set.of(UserRole.ADMIN)));
		store.save(new StructureMembership(userID, structureDID, Set.of(UserRole.R_H)));
		store.save(new StructureMembership(otherUserID, structureEID, Set.of(UserRole.ADMIN)));

		List<StructureMembership> results = store.findAdminMembershipsByUserAndTenant(userID, tenantAID);

		assertEquals(2, results.size());

		Set<UUID> foundStructureIDs = new HashSet<>();

		for (StructureMembership membership : results) {
			foundStructureIDs.add(membership.getStructureID());
			assertEquals(userID, membership.getUserID());
			assertEquals(Set.of(UserRole.ADMIN), membership.getRoles());
		}

		assertEquals(Set.of(structureAID, structureBID), foundStructureIDs);
	}

	@Test
	void returnsEmptyWhenUserIsNotAdminInTenant() {

		UUID userID = createStandardAccount();
		UUID tenantID = createTenant();
		UUID structureID = UUID.randomUUID();

		createStructure(structureID, tenantID);
		joinTenant(userID, tenantID);

		store.save(new StructureMembership(userID, structureID, Set.of(UserRole.R_H)));

		List<StructureMembership> results = store.findAdminMembershipsByUserAndTenant(userID, tenantID);

		assertTrue(results.isEmpty());
	}

}
