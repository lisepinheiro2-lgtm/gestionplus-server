package fr.gestionplus.server.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.net.http.HttpClient;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fr.gestionplus.server.account.SmsMessageSender;
import fr.gestionplus.server.account.FakeActivationMessageSender;

class FreeMobileSmsTestConfigurationTest {

	private final ApplicationContextRunner context = new ApplicationContextRunner()
			.withUserConfiguration(FreeMobileSmsTestConfiguration.class, FakeActivationMessageSender.class);

	@Test
	void doesNotActivateWithoutAnExplicitFlag() {
		context.run(application -> {
			assertNull(application.getStartupFailure());
			assertTrue(application.getBeansOfType(SmsMessageSender.class).isEmpty());
		});
		context.withPropertyValues("gestionplus.sms.free-test.enabled=false").run(application -> {
			assertNull(application.getStartupFailure());
			assertTrue(application.getBeansOfType(SmsMessageSender.class).isEmpty());
		});
	}

	@Test
	void preservesTheExistingFictitiousSenderWhenFreeIsDisabled() {
		context.withPropertyValues("gestionplus.sms.fictif=true").run(application -> {
			assertNull(application.getStartupFailure());
			assertEquals(1, application.getBeansOfType(SmsMessageSender.class).size());
			assertNotNull(application.getBean(FakeActivationMessageSender.class));
		});
	}

	@Test
	void acceptsOnlyTheExplicitLocalTestConfiguration() {
		configured().run(application -> {
			assertNull(application.getStartupFailure());
			assertNotNull(application.getBean(FreeMobileActivationMessageSender.class));
			assertEquals(1, application.getBeansOfType(SmsMessageSender.class).size());
			assertTrue(application.getBeansOfType(HttpClient.class).isEmpty());
		});
	}

	@Test
	void doesNotReplaceOrCloseAnExistingHttpClientBean() {
		HttpClient existing = mock(HttpClient.class);
		configured().withBean(HttpClient.class, () -> existing, definition -> definition.setDestroyMethodName(""))
				.run(application -> {
					assertNull(application.getStartupFailure());
					assertSame(existing, application.getBean(HttpClient.class));
				});
		verifyNoInteractions(existing);
	}

	@Test
	void refusesMissingOrUnrelatedProfiles() {
		configured().withInitializer(application -> application.getEnvironment().setActiveProfiles())
				.run(application -> assertNotNull(application.getStartupFailure()));
		configured().withInitializer(application -> application.getEnvironment().setActiveProfiles("local"))
				.run(application -> assertNotNull(application.getStartupFailure()));
	}

	@Test
	void refusesProductionEvenWhenTheTestProfileIsAlsoActive() {
		configured()
				.withInitializer(application -> application.getEnvironment().setActiveProfiles("prod", "sms-free-test"))
				.run(application -> assertNotNull(application.getStartupFailure()));
		configured().withInitializer(application -> {
			application.getEnvironment().setActiveProfiles();
			application.getEnvironment().setDefaultProfiles("prod", "sms-free-test");
		}).run(application -> assertNotNull(application.getStartupFailure()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "", "0.0.0.0", "::", "localhost", "192.168.1.10" })
	void refusesAnyOtherBindAddress(String address) {
		configured().withPropertyValues("server.address=" + address)
				.run(application -> assertNotNull(application.getStartupFailure()));
	}

	@Test
	void refusesSimultaneousFictitiousAndFreeSenders() {
		configured().withPropertyValues("gestionplus.sms.fictif=true")
				.run(application -> assertNotNull(application.getStartupFailure()));
	}

	@ParameterizedTest
	@ValueSource(strings = { "account-id", "api-key", "recipient" })
	void refusesAnyMissingCredentialOrRecipient(String property) {
		configured().withPropertyValues("gestionplus.sms.free-test." + property + "=")
				.run(application -> assertNotNull(application.getStartupFailure()));
	}

	private ApplicationContextRunner configured() {
		return context.withInitializer(application -> application.getEnvironment().setActiveProfiles("sms-free-test"))
				.withPropertyValues("gestionplus.sms.free-test.enabled=true", "server.address=127.0.0.1",
						"gestionplus.sms.fictif=false", "gestionplus.sms.free-test.account-id=12345678",
						"gestionplus.sms.free-test.api-key=fictitious-api-key",
						"gestionplus.sms.free-test.recipient=+12025550100");
	}
}
