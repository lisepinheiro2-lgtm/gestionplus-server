package fr.gestionplus.server.support;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

public final class SecurityTestRedis {

	private static final String PASSWORD = key();
	private static final String RATE_KEY = key();

	private SecurityTestRedis() {
	}

	public static void properties(DynamicPropertyRegistry properties) {
		String namespace = "test-" + UUID.randomUUID().toString().replace("-", "");
		properties.add("REDIS_HOST", () -> Holder.REDIS.getHost());
		properties.add("REDIS_PORT", () -> Holder.REDIS.getMappedPort(6379));
		properties.add("REDIS_PASSWORD", () -> PASSWORD);
		properties.add("REDIS_USER", () -> "default");
		properties.add("GESTIONPLUS_CLUSTER_NAMESPACE", () -> namespace);
		properties.add("GESTIONPLUS_RATE_LIMIT_KEY", () -> RATE_KEY);
		properties.add("gestionplus.cluster.namespace", () -> namespace);
		properties.add("gestionplus.cluster.rate-limit-key", () -> RATE_KEY);
		properties.add("spring.session.data.redis.namespace", () -> "gestionplus:" + namespace + ":sessions");
	}

	public static LettuceConnectionFactory connectionFactory() {
		LettuceConnectionFactory factory = uninitializedConnectionFactory();
		factory.afterPropertiesSet();
		factory.start();
		return factory;
	}

	public static LettuceConnectionFactory uninitializedConnectionFactory() {
		RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(
				Holder.REDIS.getHost(), Holder.REDIS.getMappedPort(6379));
		configuration.setPassword(PASSWORD);
		return new LettuceConnectionFactory(configuration);
	}

	public static StringRedisTemplate template(LettuceConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}

	public static String rateKey() {
		return RATE_KEY;
	}

	public static String host() {
		return Holder.REDIS.getHost();
	}

	public static int port() {
		return Holder.REDIS.getMappedPort(6379);
	}

	public static String password() {
		return PASSWORD;
	}

	private static String key() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	public static GenericContainer<?> isolatedContainer() {
		return new GenericContainer<>(DockerImageName.parse("redis:8.4-alpine"))
				.withExposedPorts(6379)
				.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
						new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(6379))))
				.withCommand("redis-server", "--requirepass", PASSWORD, "--notify-keyspace-events", "Egx",
						"--maxmemory", "128mb", "--maxmemory-policy", "noeviction");
	}

	private static final class Holder {

		private static final GenericContainer<?> REDIS = isolatedContainer();

		static {
			REDIS.start();
		}
	}
}
