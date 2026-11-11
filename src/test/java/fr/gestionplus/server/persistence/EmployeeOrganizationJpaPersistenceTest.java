package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Properties;
import java.util.UUID;

import org.hibernate.SessionFactory;
import org.hibernate.boot.MetadataSources;
import org.hibernate.boot.registry.StandardServiceRegistry;
import org.hibernate.boot.registry.StandardServiceRegistryBuilder;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.jpa.repository.support.JpaRepositoryFactory;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;

import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.organization.OrganizationalUnit;
import jakarta.persistence.EntityManager;

class EmployeeOrganizationJpaPersistenceTest {

	private Connection connection;
	private StandardServiceRegistry registry;
	private SessionFactory sessionFactory;
	private EntityManager entityManager;
	private EmployeeJpaRepository employeeRepository;
	private OrganizationalUnitJpaRepository unitRepository;
	private JpaEmployeeStore employeeStore;
	private JpaOrganizationalUnitStore unitStore;
	private UUID structureID;

	@BeforeEach
	void opensIsolatedInMemoryDatabase() throws Exception {

		Class<?> driverClass;

		try {
			driverClass = Class.forName("org.h2.Driver");
		} catch (ClassNotFoundException exception) {
			assumeTrue(false, "SKIPPED : pilote H2 absent ; aucune connexion PostgreSQL autorisee.");
			return;
		}

		Driver driver = (Driver) driverClass.getDeclaredConstructor().newInstance();
		connection = driver.connect("jdbc:h2:mem:gestionplus_mechanical_" + UUID.randomUUID()
				+ ";MODE=PostgreSQL", new Properties());

		ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/employee-organization-h2.sql"));
		structureID = insertStructure();

		registry = new StandardServiceRegistryBuilder().clearSettings()
				.applySetting("hibernate.connection.datasource", new SingleConnectionDataSource(connection, true))
				.applySetting("hibernate.dialect", "org.hibernate.dialect.H2Dialect")
				.applySetting("hibernate.hbm2ddl.auto", "validate")
				.build();

		sessionFactory = new MetadataSources(registry)
				.addAnnotatedClass(EmployeeEntity.class)
				.addAnnotatedClass(OrganizationalUnitEntity.class)
				.buildMetadata().buildSessionFactory();

		entityManager = sessionFactory.createEntityManager();
		JpaRepositoryFactory factory = new JpaRepositoryFactory(entityManager);
		employeeRepository = factory.getRepository(EmployeeJpaRepository.class);
		unitRepository = factory.getRepository(OrganizationalUnitJpaRepository.class);
		employeeStore = new JpaEmployeeStore(employeeRepository);
		unitStore = new JpaOrganizationalUnitStore(unitRepository);

		entityManager.getTransaction().begin();
	}

	@AfterEach
	void closesIsolatedDatabase() throws Exception {

		try {

			if (entityManager != null && entityManager.isOpen()) {

				try {

					if (entityManager.getTransaction().isActive()) {
						entityManager.getTransaction().rollback();
					}

				} finally {
					entityManager.close();
				}

			}

		} finally {

			try {

				if (sessionFactory != null) {
					sessionFactory.close();
				}

			} finally {

				try {

					if (registry != null) {
						StandardServiceRegistryBuilder.destroy(registry);
					}

				} finally {

					if (connection != null) {
						connection.close();
					}

				}

			}

		}

	}

	@Test
	void savesAndFindsUnitThroughStore() {
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", structureID);
		unitStore.createUnit(unit);
		flushAndClear();

		OrganizationalUnit restored = unitStore.findById(unit.getUnitId()).orElseThrow();

		assertEquals(unit.getUnitId(), restored.getUnitId());
		assertEquals(unit.getName(), restored.getName());
		assertEquals(structureID, restored.getStructureID());
		assertEquals(1, unitRepository.count());
	}

	@Test
	void updatesUnitWithoutChangingUuid() {
		UUID unitId = UUID.randomUUID();
		unitRepository
				.saveAndFlush(new OrganizationalUnitEntity(new OrganizationalUnit(unitId, "Initial", structureID)));
		entityManager.clear();

		unitRepository
				.saveAndFlush(new OrganizationalUnitEntity(new OrganizationalUnit(unitId, "Modifie", structureID)));
		entityManager.clear();

		OrganizationalUnit restored = unitRepository.findById(unitId).orElseThrow().toDomain();

		assertEquals(unitId, restored.getUnitId());
		assertEquals("Modifie", restored.getName());
		assertEquals(structureID, restored.getStructureID());
		assertEquals(1, unitRepository.count());
	}

	@Test
	void savesAndFindsEmployeeWithoutUnitOrStructure() {
		Employee employee = new Employee(UUID.randomUUID(), "Alex", "Exemple", 0, null, null, null);
		employeeStore.createEmployee(employee);
		flushAndClear();

		Employee restored = employeeStore.findById(employee.getEmployeeID()).orElseThrow();

		assertEmployeeEquals(employee, restored);
		assertNull(restored.getUnitId());
		assertEquals(1, employeeRepository.count());
	}

	@Test
	void savesAndFindsEmployeeWithUnit() {
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", structureID);
		unitStore.createUnit(unit);
		entityManager.flush();

		Employee employee = employeeForUnit(unit.getUnitId(), structureID);
		employeeStore.createEmployee(employee);
		flushAndClear();

		assertEmployeeEquals(employee, employeeStore.findById(employee.getEmployeeID()).orElseThrow());
		assertTrue(unitStore.findById(unit.getUnitId()).isPresent());
		assertEquals(1, employeeRepository.count());
	}

	@Test
	void updatesEmployeeAndUnitReferenceWithoutChangingUuid() {
		UUID employeeID = UUID.randomUUID();
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", structureID);
		unitStore.createUnit(unit);
		entityManager.flush();
		employeeRepository.saveAndFlush(new EmployeeEntity(
				new Employee(employeeID, "Alex", "Initial", 0, null, null, null, structureID)));
		entityManager.clear();

		Employee updated = new Employee(employeeID, "Camille", "Modifie", 28.5,
				"camille@example.invalid", "0600000000", unit.getUnitId(), structureID);
		employeeRepository.saveAndFlush(new EmployeeEntity(updated));
		entityManager.clear();

		assertEmployeeEquals(updated, employeeStore.findById(employeeID).orElseThrow());

		Employee detached = new Employee(employeeID, "Camille", "Modifie", 28.5,
				"camille@example.invalid", "0600000000", null, structureID);
		employeeRepository.saveAndFlush(new EmployeeEntity(detached));
		entityManager.clear();

		assertEmployeeEquals(detached, employeeStore.findById(employeeID).orElseThrow());
		assertEquals(1, employeeRepository.count());
		assertTrue(unitStore.findById(unit.getUnitId()).isPresent());
	}

	@Test
	void returnsEmptyForUnknownIds() {
		assertTrue(employeeStore.findById(UUID.randomUUID()).isEmpty());
		assertTrue(unitStore.findById(UUID.randomUUID()).isEmpty());
	}

	@Test
	void rejectsEmployeeWithUnknownUnit() {
		UUID unitId = UUID.randomUUID();
		assertFalse(unitRepository.existsById(unitId));

		assertThrows(ConstraintViolationException.class,
				() -> employeeRepository.saveAndFlush(new EmployeeEntity(employeeForUnit(unitId, structureID))));
	}

	@Test
	void rejectsUnitWithoutStructure() {
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test");

		assertThrows(ConstraintViolationException.class,
				() -> unitRepository.saveAndFlush(new OrganizationalUnitEntity(unit)));
	}

	@Test
	void rejectsEmployeeWithUnitButWithoutStructure() {
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", structureID);
		unitStore.createUnit(unit);
		entityManager.flush();

		Employee employee = employeeForUnit(unit.getUnitId(), null);
		assertThrows(ConstraintViolationException.class,
				() -> employeeRepository.saveAndFlush(new EmployeeEntity(employee)));
	}

	@Test
	void rejectsEmployeeWithUnitFromAnotherStructure() throws SQLException {
		UUID otherStructureID = insertStructure();
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", otherStructureID);
		unitStore.createUnit(unit);
		entityManager.flush();

		Employee employee = employeeForUnit(unit.getUnitId(), structureID);
		assertThrows(ConstraintViolationException.class,
				() -> employeeRepository.saveAndFlush(new EmployeeEntity(employee)));
	}

	@Test
	void refusesDeletingReferencedUnitWithoutCascading() {
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", structureID);
		unitStore.createUnit(unit);
		entityManager.flush();

		Employee employee = employeeForUnit(unit.getUnitId(), structureID);
		employeeStore.createEmployee(employee);
		entityManager.getTransaction().commit();
		entityManager.clear();
		entityManager.getTransaction().begin();

		assertThrows(ConstraintViolationException.class, () -> {
			unitRepository.deleteById(unit.getUnitId());
			unitRepository.flush();
		});

		entityManager.getTransaction().rollback();
		entityManager.clear();
		entityManager.getTransaction().begin();

		assertTrue(unitStore.findById(unit.getUnitId()).isPresent());
		assertEmployeeEquals(employee, employeeStore.findById(employee.getEmployeeID()).orElseThrow());
	}

	@Test
	void deletingEmployeeKeepsUnitAndOtherEmployees() {
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", structureID);
		unitStore.createUnit(unit);
		entityManager.flush();

		Employee employee = employeeForUnit(unit.getUnitId(), structureID);
		Employee other = employeeForUnit(unit.getUnitId(), structureID);
		employeeStore.createEmployee(employee);
		employeeStore.createEmployee(other);
		flushAndClear();

		employeeRepository.deleteById(employee.getEmployeeID());
		flushAndClear();

		assertTrue(employeeStore.findById(employee.getEmployeeID()).isEmpty());
		assertEmployeeEquals(other, employeeStore.findById(other.getEmployeeID()).orElseThrow());
		assertTrue(unitStore.findById(unit.getUnitId()).isPresent());
		assertEquals(1, employeeRepository.count());
	}

	@Test
	void deletesUnreferencedUnit() {
		OrganizationalUnit unit = new OrganizationalUnit(UUID.randomUUID(), "Secteur de test", structureID);
		unitStore.createUnit(unit);
		flushAndClear();

		unitRepository.deleteById(unit.getUnitId());
		flushAndClear();

		assertTrue(unitStore.findById(unit.getUnitId()).isEmpty());
		assertEquals(0, unitRepository.count());
	}

	private UUID insertStructure() throws SQLException {
		UUID id = UUID.randomUUID();

		try (PreparedStatement statement = connection.prepareStatement(
				"INSERT INTO organizational_structures (structure_id) VALUES (?)")) {
			statement.setObject(1, id);
			statement.executeUpdate();
		}

		return id;
	}

	private Employee employeeForUnit(UUID unitId, UUID structureID) {
		return new Employee(UUID.randomUUID(), "Camille", "Exemple", 28.5,
				"camille@example.invalid", "0600000000", unitId, structureID);
	}

	private void flushAndClear() {
		entityManager.flush();
		entityManager.clear();
	}

	private void assertEmployeeEquals(Employee expected, Employee restored) {
		assertEquals(expected.getEmployeeID(), restored.getEmployeeID());
		assertEquals(expected.getFirstName(), restored.getFirstName());
		assertEquals(expected.getLastName(), restored.getLastName());
		assertEquals(expected.getContractHours(), restored.getContractHours());
		assertEquals(expected.getEmail(), restored.getEmail());
		assertEquals(expected.getPhoneNumber(), restored.getPhoneNumber());
		assertEquals(expected.getUnitId(), restored.getUnitId());
		assertEquals(expected.getStructureID(), restored.getStructureID());
	}
}
