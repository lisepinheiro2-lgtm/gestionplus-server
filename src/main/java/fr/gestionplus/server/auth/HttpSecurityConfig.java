package fr.gestionplus.server.auth;

import java.util.Objects;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class HttpSecurityConfig {

	private final GestionAuthenticationProvider authenticationProvider;

	public HttpSecurityConfig(GestionAuthenticationProvider authenticationProvider) {
		this.authenticationProvider = Objects.requireNonNull(authenticationProvider);
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

		http.formLogin(form -> form.permitAll().successHandler((request, response, authentication) -> {
			response.setStatus(200);
		})

				.failureHandler((request, response, exception) -> {
					response.setStatus(401);
				}));

		http.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {
			response.setStatus(401);
		}));

		http.logout(logout -> logout.logoutSuccessHandler((request, response, authentication) -> {
			response.setStatus(204);
		}));

		http.authorizeHttpRequests(auth -> auth.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/me").authenticated().anyRequest().denyAll());

		http.authenticationProvider(authenticationProvider);
		return http.build();
	}
}
