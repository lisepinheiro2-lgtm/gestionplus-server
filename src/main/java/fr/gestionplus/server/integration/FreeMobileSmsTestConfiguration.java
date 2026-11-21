package fr.gestionplus.server.integration;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gestionplus.sms.free-test.enabled", havingValue = "true")
public class FreeMobileSmsTestConfiguration {

	@Bean(destroyMethod = "close")
	public FreeMobileActivationMessageSender freeMobileActivationMessageSender(Environment environment) {

		if (!environment.matchesProfiles("sms-free-test") || environment.matchesProfiles("prod")) {
			throw new IllegalStateException(
					"L’expéditeur SMS Free est réservé au profil sms-free-test, hors production.");
		}

		if (!"127.0.0.1".equals(environment.getProperty("server.address"))) {
			throw new IllegalStateException("L’expéditeur SMS Free de test exige une écoute sur 127.0.0.1.");
		}

		if ("true".equalsIgnoreCase(environment.getProperty("gestionplus.sms.fictif"))) {
			throw new IllegalStateException("Les expéditeurs SMS fictif et Free ne peuvent pas être activés ensemble.");
		}

		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();

		try {
			return new FreeMobileActivationMessageSender(client,
					environment.getProperty("gestionplus.sms.free-test.account-id", ""),
					environment.getProperty("gestionplus.sms.free-test.api-key", ""),
					environment.getProperty("gestionplus.sms.free-test.recipient", ""));
		} catch (RuntimeException failure) {
			client.close();
			throw failure;
		}

	}
}
