package fr.gestionplus.server.auth;

import java.util.Map;

import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.IndexResolver;
import org.springframework.session.Session;
import org.springframework.session.config.SessionRepositoryCustomizer;
import org.springframework.session.data.redis.RedisIndexedSessionRepository;
import org.springframework.session.data.redis.RedisSessionMapper;
import org.springframework.session.data.redis.config.ConfigureRedisAction;

import fr.gestionplus.server.persistence.RedisRequestRateLimiter;
import fr.gestionplus.server.persistence.RedisSecuritySupport;

@Configuration(proxyBeanMethods = false)
public class DistributedSessionConfiguration {

	@Bean
	public SmartInitializingSingleton sharedSecurityAvailability(RedisSecuritySupport redis, Environment environment) {
		return () -> redis.verifyConfiguration(environment.getProperty("gestionplus.mfa.key", ""),
				environment.getProperty("gestionplus.activation.delivery-key", ""),
				environment.getProperty("gestionplus.activation.challenge-key", ""));
	}

	@Bean
	public SessionRegistry sessionRegistery(FindByIndexNameSessionRepository<? extends Session> sessions,
			SessionActivityStore activity) {
		return new DistributedSessionRegistry(sessions, activity);
	}

	@Bean
	public SessionLifetime sessionLifetime(RedisSecuritySupport redis, SessionActivityStore activity) {
		return new SessionLifetime(redis.clock(), activity);
	}

	@Bean
	public SessionValueSerializer springSessionDefaultRedisSerializer() {
		return new SessionValueSerializer();
	}

	@Bean
	public IndexResolver<Session> sessionIndexResolver() {
		return session -> {
			Object value = session.getAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

			if (value instanceof SecurityContext context && context.getAuthentication() != null
					&& context.getAuthentication().getPrincipal() instanceof UserAccount account) {
				return Map.of(FindByIndexNameSessionRepository.PRINCIPAL_NAME_INDEX_NAME,
						account.getUserID().toString());
			}

			return Map.of();
		};
	}

	@Bean
	public ConfigureRedisAction configureRedisAction() {
		return ConfigureRedisAction.NO_OP;
	}

	@Bean
	public SessionRepositoryCustomizer<RedisIndexedSessionRepository> safeSessionMapper() {
		return repository -> {
			RedisSessionMapper mapper = new RedisSessionMapper();
			repository.setRedisSessionMapper((id, attributes) -> {

				if (!attributes.containsKey("creationTime") || !attributes.containsKey("lastAccessedTime")
						|| !attributes.containsKey("maxInactiveInterval")) {
					return null;
				}

				return mapper.apply(id, attributes);
			});
		};
	}

	@Bean
	public RequestRateLimiter requestRateLimiter(RedisSecuritySupport redis) {
		return new RedisRequestRateLimiter(redis);
	}
}
