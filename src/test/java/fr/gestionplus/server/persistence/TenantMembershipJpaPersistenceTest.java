
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import fr.gestionplus.server.organization.TenantMembership;
import fr.gestionplus.server.organization.TenantMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_tenant_membership_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true" })
class TenantMembershipJpaPersistenceTest extends AuditStorageIntegrationTest {

	@Autowired
	private TenantMembershipStore store;
	@Autowired
	private TenantMembershipJpaRepository membershipRepository;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;
	@Autowired
	private TenantOrganizationJpaRepository tenantRepository;
	@Autowired
	private JdbcTemplate jdbcTemplate;

	@Test
	void savesAndFindsTenantMembership() {

		UUID userID = UUID.randomUUID();
		UUID tenantID = UUID.randomUUID();

		Employee employee = new Employee(UUID.randomUUID(), "Alice", "Test", 35, "alice@example.invalid", "0600000000",
				null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "alice_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.SUPER_ADMIN), employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(),
				employee.getEmail(), employee.getPhoneNumber());
		accountRepository.saveAndFlush(new UserAccountEntity(account));

		TenantOrganization tenant = new TenantOrganization(tenantID, "Entreprise test", userID);
		tenantRepository.saveAndFlush(new TenantOrganizationEntity(tenant));

		TenantMembership membership = new TenantMembership(userID, tenantID);
		membershipRepository.saveAndFlush(new TenantMembershipEntity(membership));

		TenantMembership restored = store.findByUserAndTenant(userID, tenantID).orElseThrow();

		assertEquals(userID, restored.getUserID());
		assertEquals(tenantID, restored.getTenantID());
	}

	@Test
	void returnsEmptyWhenTenantMembershipDoesNotExist() {

		UUID userID = UUID.randomUUID();
		UUID tenantID = UUID.randomUUID();

		assertTrue(store.findByUserAndTenant(userID, tenantID).isEmpty());
	}

	private UUID createTestAccount(UserRole role) {

		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Alice", "Test", 35, "alice_" + userID + "@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "alice_" + userID, UserAccountStatus.ACTIVE, Set.of(role),
				employee.getEmployeeID(), employee.getLastName(), employee.getFirstName(), employee.getEmail(),
				employee.getPhoneNumber());
		accountRepository.saveAndFlush(new UserAccountEntity(account));

		return userID;
	}

	private UUID createTestTenant() {

		UUID superAdminID = createTestAccount(UserRole.SUPER_ADMIN);
		UUID tenantID = UUID.randomUUID();

		TenantOrganization tenant = new TenantOrganization(tenantID, "Entreprise test", superAdminID);
		tenantRepository.saveAndFlush(new TenantOrganizationEntity(tenant));

		return tenantID;
	}

	@Test
	void rejectsDuplicateTenantMembership() {

		UUID userID = createTestAccount(UserRole.EMPLOYEE);
		UUID tenantID = createTestTenant();

		jdbcTemplate.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);

		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate
				.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID));
		assertEquals(1L,
				jdbcTemplate.queryForObject(
						"SELECT COUNT(*) FROM tenant_memberships " + "WHERE user_id = ? AND tenant_id = ?", Long.class,
						userID, tenantID));
	}

	@Test
	void rejectsTenantMembershipWithUnknownUser() {

		UUID unknownUserID = UUID.randomUUID();
		UUID tenantID = createTestTenant();

		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate
				.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", unknownUserID, tenantID));
	}

	@Test
	void rejectsTenantMembershipWithUnknownTenant() {

		UUID userID = createTestAccount(UserRole.EMPLOYEE);
		UUID unknownTenantID = UUID.randomUUID();

		assertThrows(DataIntegrityViolationException.class, () -> jdbcTemplate
				.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, unknownTenantID));
	}

	@Test
	void createsTenantMembershipThroughStore() {

		UUID userID = createTestAccount(UserRole.EMPLOYEE);
		UUID tenantID = createTestTenant();

		TenantMembership membership = new TenantMembership(userID, tenantID);

		store.save(membership);

		TenantMembership restored = store.findByUserAndTenant(userID, tenantID).orElseThrow();

		assertEquals(userID, restored.getUserID());
		assertEquals(tenantID, restored.getTenantID());
	}

}
