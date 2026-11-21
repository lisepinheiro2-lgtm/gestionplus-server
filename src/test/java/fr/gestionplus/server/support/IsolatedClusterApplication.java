package fr.gestionplus.server.support;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import fr.gestionplus.server.GestionplusServerApplication;
import fr.gestionplus.server.account.ActivationMessageSender;
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
		ActivationMessageSender isolatedMessageSender() {
			return (phone, code) -> {
				throw new IllegalStateException("Les envois SMS sont interdits dans cette validation.");
			};
		}

		@Bean
		@Primary
		CompromisedPasswordChecker isolatedPasswordChecker() {
			return password -> false;
		}

		@Bean
		WebMvcConfigurer delayedRead(@Value("${cluster.test.control-directory}") String directory) {
			return new WebMvcConfigurer() {
				@Override
				public void addInterceptors(InterceptorRegistry registry) {
					registry.addInterceptor(new HandlerInterceptor() {
						@Override
						public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
								Object handler)
								throws Exception {

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
