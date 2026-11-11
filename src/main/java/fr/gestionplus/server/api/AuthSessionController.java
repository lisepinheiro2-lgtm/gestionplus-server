package fr.gestionplus.server.api;

import java.util.Map;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RestController;

import fr.gestionplus.server.auth.SessionLifetime;
import fr.gestionplus.server.auth.UserAccount;

@RestController
public class AuthSessionController {

	@GetMapping(SessionLifetime.STATUS_PATH)
	public SessionLifetime.Expiration getSessionExpiration(
			@RequestAttribute(SessionLifetime.REQUEST_EXPIRATION) SessionLifetime.Expiration expiration) {
		return expiration;
	}

	@GetMapping("/api/auth/me")
	public Map<String, String> getCurrentUser(Authentication authentication) {

		if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount)
				|| !authentication.isAuthenticated()) {
			throw new AuthenticationCredentialsNotFoundException("Vous ne passerez paaaaas !!!");
		}

		UserAccount account = (UserAccount) authentication.getPrincipal();
		return Map.of("userName", account.getUserName());
	}
}
