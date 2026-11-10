
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.TenantOrganization;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_structure_jpa_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true" })

@Transactional
class OrganizationalStructureJpaPersistenceTest {

	@Autowired
	private OrganizationalStructureJpaRepository repository;

	@Autowired
	private TenantOrganizationJpaRepository tenantRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;
	@Autowired
	private EmployeeJpaRepository employeeRepository;

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void savesAndFindsStructureOnPostgresql() {

		UUID structureID = UUID.randomUUID();
		UUID tenantID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Super", "Admin", 35, "super@example.invalid",
				"0600000000", null);
		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UUID userID = UUID.randomUUID();
		UserAccount account = new UserAccount(userID, "super_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.SUPER_ADMIN), employee.getEmployeeID(),
				employee.getLastName(), employee.getFirstName(), employee.getEmail(), employee.getPhoneNumber());
		accountRepository.saveAndFlush(new UserAccountEntity(account));
		TenantOrganization tenant = new TenantOrganization(tenantID, "Entreprise", userID);
		tenantRepository.saveAndFlush(new TenantOrganizationEntity(tenant));

		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Association A", tenantID);

		repository.saveAndFlush(new OrganizationalStructureEntity(structure));
		entityManager.clear();

		OrganizationalStructure restored = repository.findById(structureID).orElseThrow().toDomain();

		assertEquals(structure.getStructureID(), restored.getStructureID());
		assertEquals(structure.getStructureName(), restored.getStructureName());
		assertEquals(tenantID, restored.getTenantID());
		TenantOrganization restoredTenant = tenantRepository.findById(tenantID).orElseThrow().toDomain();
		assertEquals(tenant.getTenantName(), restoredTenant.getTenantName());
		assertEquals(account.getUserID(), restoredTenant.getSuperAdminUserID());
	}
}
