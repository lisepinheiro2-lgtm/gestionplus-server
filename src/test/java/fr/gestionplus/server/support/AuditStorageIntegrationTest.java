package fr.gestionplus.server.support;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

public abstract class AuditStorageIntegrationTest {

	@DynamicPropertySource
	static void auditStorage(DynamicPropertyRegistry properties) {
		Path directory = createDirectory();
		String key = createKey();
		String deliveryKey = createKey();
		properties.add("gestionplus.audit.fallback.key", () -> key);
		properties.add("gestionplus.audit.fallback.directory", directory::toString);
		properties.add("gestionplus.activation.delivery-key", () -> deliveryKey);
		properties.add("gestionplus.activation.delivery-initial-delay", () -> "86400000");
	}

	private static Path createDirectory() {

		try {
			return Files.createTempDirectory("gestionplus-audit-tests-");
		} catch (IOException failure) {
			throw new IllegalStateException(failure);
		}

	}

	private static String createKey() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}
}
