package fr.gestionplus.server.auth;

import java.net.http.HttpClient;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import fr.gestionplus.server.integration.PwnedPasswordsChecker;

@Configuration
public class PasswordSecurityConfig {

	@Bean
	public PasswordEncoder passwordEncoder() {

		return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
	}

	@Bean
	public PasswordPolicy passwordPolicy(CompromisedPasswordChecker checker) {
		return new PasswordPolicy(checker);
	}

	@Bean
	public CompromisedPasswordChecker compromisedPasswordChecker(HttpClient client) {
		return new PwnedPasswordsChecker(client);
	}
	
	@Bean
	public HttpClient httpClient() {
		return HttpClient.newHttpClient();
	}
}
