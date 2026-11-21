package fr.gestionplus.server.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

@TestConfiguration(proxyBeanMethods = false)
public class RedisIntegrationTestConfiguration {

	@Bean
	LettuceConnectionFactory isolatedRedisConnectionFactory() {
		return SecurityTestRedis.uninitializedConnectionFactory();
	}
}
