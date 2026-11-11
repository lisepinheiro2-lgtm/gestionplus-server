package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import java.nio.file.Path;
import java.util.Base64;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

class AuditFallbackConfigurationTest {

	@TempDir
	Path directory;

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withBean(JdbcTemplate.class, () -> mock(JdbcTemplate.class))
			.withBean(PlatformTransactionManager.class, () -> mock(PlatformTransactionManager.class))
			.withUserConfiguration(PostgresqlAuditFallback.class);

	@Test
	void refusesMissingAndPartialConfiguration() {
		context.run(application -> assertNotNull(application.getStartupFailure()));
		context.withPropertyValues("gestionplus.audit.fallback.directory=" + directory)
				.run(application -> assertNotNull(application.getStartupFailure()));
		context.withPropertyValues("gestionplus.audit.fallback.key=" + key())
				.run(application -> assertNotNull(application.getStartupFailure()));
	}

	@Test
	void refusesAnInvalidKeyBlankDirectoryOrMissingDirectory() {
		context.withPropertyValues("gestionplus.audit.fallback.key=false",
				"gestionplus.audit.fallback.directory=" + directory)
				.run(application -> assertNotNull(application.getStartupFailure()));
		context.withPropertyValues("gestionplus.audit.fallback.key=" + key(),
				"gestionplus.audit.fallback.directory=")
				.run(application -> assertNotNull(application.getStartupFailure()));
		context.withPropertyValues("gestionplus.audit.fallback.key=" + key(),
				"gestionplus.audit.fallback.directory=" + directory.resolve("missing"))
				.run(application -> assertNotNull(application.getStartupFailure()));
	}

	@Test
	void acceptsAnExplicitPrivateJournalAndA256BitKey() {
		context.withPropertyValues("gestionplus.audit.fallback.key=" + key(),
				"gestionplus.audit.fallback.directory=" + directory)
				.run(application -> {
					assertNull(application.getStartupFailure());
					assertNotNull(application.getBean(PostgresqlAuditFallback.class));
				});
	}

	private String key() {
		return Base64.getEncoder().encodeToString(new byte[32]);
	}
}
