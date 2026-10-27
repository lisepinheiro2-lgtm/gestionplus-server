package fr.gestionplus.server.auth;

import java.util.Objects;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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

		http.authorizeHttpRequests(auth -> auth.anyRequest().denyAll());
		http.authenticationProvider(authenticationProvider);
		return http.build();
	}
}
