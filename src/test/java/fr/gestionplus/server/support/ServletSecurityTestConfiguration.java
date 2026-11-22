package fr.gestionplus.server.support;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.servlet.AbstractFilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;

import fr.gestionplus.server.auth.LoginAttemptLimiter;
import fr.gestionplus.server.auth.RequestRateLimiter;
import fr.gestionplus.server.auth.SessionLifetime;

@TestConfiguration(proxyBeanMethods = false)
public class ServletSecurityTestConfiguration {

	@Bean
	@Primary
	SessionRegistry servletSessionRegistry() {
		return new SessionRegistryImpl();
	}

	@Bean
	@Primary
	SessionLifetime servletSessionLifetime(ObjectProvider<Clock> clocks) {
		return new SessionLifetime(clocks.getIfAvailable(Clock::systemUTC));
	}

	@Bean
	@ConditionalOnMissingBean(LoginAttemptLimiter.class)
	LoginAttemptLimiter servletLoginAttemptLimiter() {
		return new LoginAttemptLimiter() {
			@Override
			public Attempt begin(String userName, String address) {
				return new Attempt("servlet-test-account", "servlet-test-address", UUID.randomUUID().toString());
			}

			@Override
			public boolean finish(Attempt attempt, boolean failed, boolean knownAccount) {
				return false;
			}
		};
	}

	@Bean
	@ConditionalOnMissingBean(RequestRateLimiter.class)
	RequestRateLimiter servletRequestRateLimiter() {
		return new RequestRateLimiter() {
			@Override
			public Decision acquire(String scope, String address, int limit, Duration window) {
				return new Decision(true, Duration.ZERO);
			}

			@Override
			public Decision acquireForAccount(String scope, UUID userID, int limit, Duration window) {
				return new Decision(true, Duration.ZERO);
			}
		};
	}

	@Bean
	static BeanPostProcessor servletSessionFilterRegistration() {
		return new BeanPostProcessor() {
			@Override
			public Object postProcessAfterInitialization(Object bean, String name) {

				if ("sessionRepositoryFilterRegistration".equals(name)
						&& bean instanceof AbstractFilterRegistrationBean<?> registration) {
					registration.setEnabled(false);
				}

				return bean;
			}
		};
	}
}
