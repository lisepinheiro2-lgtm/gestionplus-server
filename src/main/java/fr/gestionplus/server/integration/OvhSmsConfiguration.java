package fr.gestionplus.server.integration;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "gestionplus.sms.provider", havingValue = "ovh")
public class OvhSmsConfiguration {

	@Bean(destroyMethod = "close")
	public OvhSmsActivationMessageSender ovhSmsActivationMessageSender(Environment environment) {
		if (environment.getProperty("gestionplus.sms.fictif", Boolean.class, false)
				|| environment.getProperty("gestionplus.sms.free-test.enabled", Boolean.class, false)) {
			throw new IllegalStateException("Un seul expéditeur SMS peut être activé à la fois.");
		}
		HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NEVER).proxy(HttpClient.Builder.NO_PROXY).build();
		try {
			return new OvhSmsActivationMessageSender(client,
					environment.getProperty("gestionplus.sms.ovh.application-key", ""),
					environment.getProperty("gestionplus.sms.ovh.application-secret", ""),
					environment.getProperty("gestionplus.sms.ovh.consumer-key", ""),
					environment.getProperty("gestionplus.sms.ovh.service-name", ""),
					environment.getProperty("gestionplus.sms.ovh.sender", ""));
		} catch (RuntimeException failure) {
			client.shutdownNow();
			throw failure;
		}
	}
}
