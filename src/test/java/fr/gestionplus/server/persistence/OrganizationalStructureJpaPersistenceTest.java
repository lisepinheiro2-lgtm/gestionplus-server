
package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.organization.OrganizationalStructure;

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

	@PersistenceContext
	private EntityManager entityManager;

	@Test
	void savesAndFindsStructureOnPostgresql() {

		UUID structureID = UUID.randomUUID();
		OrganizationalStructure structure = new OrganizationalStructure(structureID, "Association A");

		repository.saveAndFlush(new OrganizationalStructureEntity(structure));
		entityManager.clear();

		OrganizationalStructure restored = repository.findById(structureID).orElseThrow().toDomain();

		assertEquals(structure.getStructureID(), restored.getStructureID());
		assertEquals(structure.getStructureName(), restored.getStructureName());
	}
}
