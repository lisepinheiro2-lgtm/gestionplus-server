package fr.gestionplus.server.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.SuccessionOffer;
import fr.gestionplus.server.account.SuccessionOffer.OfferStatus;
import fr.gestionplus.server.account.SuccessionOfferStore;
import fr.gestionplus.server.persistence.PostgresqlSuccessionOfferStore;

public final class SuccessionOfferCreationProcess {

	private SuccessionOfferCreationProcess() { }

	public static void main(String[] args) throws Exception {
		Properties settings = new Properties();
		try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) { settings.load(reader); }
		Instant now = Instant.parse(settings.getProperty("now"));
		var source = new DriverManagerDataSource(settings.getProperty("url"), settings.getProperty("user"), settings.getProperty("password"));
		var manager = new DataSourceTransactionManager(source);
		ProxyFactory proxy = new ProxyFactory(new PostgresqlSuccessionOfferStore(new JdbcTemplate(source), Clock.fixed(now, ZoneOffset.UTC)));
		proxy.addAdvice(new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
		SuccessionOfferStore store = (SuccessionOfferStore) proxy.getProxy();
		SuccessionOffer offer = new SuccessionOffer(UUID.randomUUID(), UUID.fromString(settings.getProperty("outgoing")),
				UUID.fromString(settings.getProperty("successor")), UUID.fromString(settings.getProperty("tenant")),
				List.of(), "a".repeat(64), OfferStatus.PENDING, now, now.plusSeconds(86400));
		Path directory = Path.of(settings.getProperty("directory"));
		String node = settings.getProperty("node");
		Files.writeString(directory.resolve(node + "-ready"), "ready");
		long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
		while (!Files.exists(directory.resolve("release"))) {
			if (System.nanoTime() >= end) { throw new IllegalStateException("Barrière de concurrence fictive non libérée."); }
			Thread.sleep(20);
		}
		String result;
		try {
			new TransactionTemplate(manager).executeWithoutResult(status -> store.create(offer));
			result = "CREATED";
		} catch (AccessDeniedException denied) {
			result = "REFUSED";
		}
		Files.writeString(directory.resolve(node + "-result"), result);
	}
}
