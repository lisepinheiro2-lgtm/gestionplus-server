package fr.gestionplus.server;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@ActiveProfiles("test")
@SpringBootTest
class GestionplusServerApplicationTests extends AuditStorageIntegrationTest {

	@Test
	void contextLoads() {
	}

}
