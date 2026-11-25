package fr.gestionplus.server.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import fr.gestionplus.server.GestionplusServerApplication;
import fr.gestionplus.server.account.SmsMessageSender;
import fr.gestionplus.server.account.SuperAdminSuccessionService;
import fr.gestionplus.server.auth.CompromisedPasswordChecker;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public final class IsolatedClusterApplication {

	private IsolatedClusterApplication() {
	}

	public static void main(String[] args) throws Exception {
		Properties properties = new Properties();

		try (var reader = Files.newBufferedReader(Path.of(args[0]), StandardCharsets.UTF_8)) {
			properties.load(reader);
		}

		String[] arguments = properties.stringPropertyNames().stream()
				.map(name -> "--" + name + "=" + properties.getProperty(name)).toArray(String[]::new);
		var context = new SpringApplicationBuilder(GestionplusServerApplication.class, ClusterTestConfiguration.class)
				.run(arguments);
		int port = ((WebServerApplicationContext) context).getWebServer().getPort();
		Path ready = Path.of(properties.getProperty("cluster.test.control-directory"),
				properties.getProperty("cluster.test.node") + "-ready.txt");
		Files.writeString(ready, Integer.toString(port), StandardCharsets.UTF_8);
	}

	@TestConfiguration(proxyBeanMethods = false)
	public static class ClusterTestConfiguration {

		@Bean
		SmsMessageSender isolatedMessageSender() {
			return (purpose, phone, code) -> {
				throw new IllegalStateException("Les envois SMS sont interdits dans cette validation.");
			};
		}

		@Bean
		@Primary
		CompromisedPasswordChecker isolatedPasswordChecker() {
			return password -> false;
		}

		@Bean
		WebMvcConfigurer delayedRead(@Value("${cluster.test.control-directory}") String directory,
				@Value("${cluster.test.node}") String node,
				SuperAdminSuccessionService succession) {
			return new WebMvcConfigurer() {
				@Override
				public void addInterceptors(InterceptorRegistry registry) {
					registry.addInterceptor(new HandlerInterceptor() {
						@Override
						public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
								Object handler)
								throws Exception {

							String offerID = request.getHeader("X-Cluster-Test-Finalize-Offer");

							if (offerID != null) {
								UUID target = UUID.fromString(offerID);
								Path root = Path.of(directory);
								Files.writeString(root.resolve("succession-" + target + "-" + node + ".marker"),
										"ready");
								String other = "first".equals(node) ? "second" : "first";
								Path otherMarker = root.resolve("succession-" + target + "-" + other + ".marker");
								long deadline = System.nanoTime() + Duration.ofSeconds(12).toNanos();

								while (!Files.exists(otherMarker)) {

									if (System.nanoTime() >= deadline) {
										response.setStatus(503);
										return false;
									}

									Thread.sleep(20);
								}

								try {
									succession.finalizeSuccession(target);
									response.setStatus(204);
								} catch (AccessDeniedException denied) {
									response.setStatus(403);
								}

								return false;
							}

							if (!"true".equals(request.getHeader("X-Cluster-Test-Delay"))) {
								return true;
							}

							Path root = Path.of(directory);
							Files.writeString(root.resolve("read-blocked.marker"), "blocked");
							long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();

							while (!Files.exists(root.resolve("release-read.marker"))) {

								if (System.nanoTime() >= deadline) {
									response.setStatus(503);
									return false;
								}

								Thread.sleep(20);
							}

							return true;
						}
					}).addPathPatterns("/api/auth/me");
				}
			};
		}
	}
}
