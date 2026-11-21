package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.session.FindByIndexNameSessionRepository;

import fr.gestionplus.server.persistence.RedisRequestRateLimiter;
import fr.gestionplus.server.persistence.RedisSecuritySupport;

class DistributedSessionConfigurationTest {

	@Test
	void providesExactlyOneRequestRateLimiterWithTheProductionConfigurationAndComponent() {
		new ApplicationContextRunner()
				.withUserConfiguration(DistributedSessionConfiguration.class, RedisRequestRateLimiter.class)
				.withBean(RedisSecuritySupport.class, () -> mock(RedisSecuritySupport.class))
				.withBean(SessionActivityStore.class, () -> mock(SessionActivityStore.class))
				.withBean(FindByIndexNameSessionRepository.class, () -> mock(FindByIndexNameSessionRepository.class))
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertEquals(1, context.getBeansOfType(RequestRateLimiter.class).size());
					assertInstanceOf(RedisRequestRateLimiter.class, context.getBean(RequestRateLimiter.class));
				});
	}
}
