
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
import fr.gestionplus.server.organization.StructureMembership;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@SpringBootTest(properties = { "spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_membership_jpa_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true" })
@Transactional
class StructureMembershipJpaPersistenceTest {

	@Autowired
	private StructureMembershipJpaRepository membershipRepository;
	@Autowired
	private OrganizationalStructureJpaRepository structureRepository;
	@Autowired
	private UserAccountJpaRepository accountRepository;
	@Autowired
	private EmployeeJpaRepository employeeRepository;
	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void preservesDifferentRolesForSameUserAcrossStructures() {

		UUID userID = UUID.randomUUID();
		Employee employee = new Employee(UUID.randomUUID(), "Alice", "Test", 35, "alice@example.invalid", "0600000000",
				null);

		employeeRepository.saveAndFlush(new EmployeeEntity(employee));

		UserAccount account = new UserAccount(userID, "alice_" + userID, UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), employee.getEmployeeID());

		accountRepository.saveAndFlush(new UserAccountEntity(account));

		OrganizationalStructure structureA = new OrganizationalStructure(UUID.randomUUID(), "Structure A");
		OrganizationalStructure structureB = new OrganizationalStructure(UUID.randomUUID(), "Structure B");

		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureA));
		structureRepository.saveAndFlush(new OrganizationalStructureEntity(structureB));

		StructureMembership membershipA = new StructureMembership(userID, structureA.getStructureID(),
				Set.of(UserRole.ADMIN, UserRole.ACCOUNTANT));
		StructureMembership membershipB = new StructureMembership(userID, structureB.getStructureID(),
				Set.of(UserRole.EMPLOYEE));

		membershipRepository.saveAndFlush(new StructureMembershipEntity(membershipA));
		membershipRepository.saveAndFlush(new StructureMembershipEntity(membershipB));

		entityManager.clear();

		StructureMembership restoredA = membershipRepository
				.findById(new StructureMembershipId(userID, structureA.getStructureID())).orElseThrow().toDomain();
		StructureMembership restoredB = membershipRepository
				.findById(new StructureMembershipId(userID, structureB.getStructureID())).orElseThrow().toDomain();

		assertEquals(userID, restoredA.getUserID());
		assertEquals(structureA.getStructureID(), restoredA.getStructureID());
		assertEquals(membershipA.getRoles(), restoredA.getRoles());
		assertEquals(userID, restoredB.getUserID());
		assertEquals(structureB.getStructureID(), restoredB.getStructureID());
		assertEquals(membershipB.getRoles(), restoredB.getRoles());
		assertEquals(2, membershipRepository.count());
	}
}
