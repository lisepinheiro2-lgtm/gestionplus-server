package fr.gestionplus.server.auth;

import java.util.Objects;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class HttpSecurityConfig {

	private final GestionAuthenticationProvider authenticationProvider;

	public HttpSecurityConfig(GestionAuthenticationProvider authenticationProvider) {
		this.authenticationProvider = Objects.requireNonNull(authenticationProvider);
	}

	@Bean
	public RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.fromHierarchy("""
				ROLE_SUPER_ADMIN > ROLE_ADMIN
				ROLE_ADMIN > ROLE_R_H
				ROLE_ADMIN > ROLE_ACCOUNTANT
				ROLE_R_H > ROLE_MANAGER
				ROLE_R_H > ROLE_SECRETARY
				ROLE_MANAGER > ROLE_TEAM_LEAD
				ROLE_SECRETARY > ROLE_TEAM_LEAD
				ROLE_TEAM_LEAD > ROLE_EMPLOYEE
				""");
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry registry) throws Exception {

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

		http.sessionManagement(sessions -> sessions.maximumSessions(-1).sessionRegistry(registry)
				.expiredSessionStrategy(event -> event.getResponse().setStatus(401)));

		http.authenticationProvider(authenticationProvider);
		return http.build();
	}

	@Bean
	public SessionRegistry sessionRegistery() {
		SessionRegistry registry = new SessionRegistryImpl();
		return registry;
	}
}
