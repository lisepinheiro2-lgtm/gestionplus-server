package fr.gestionplus.server.api;

import java.util.Map;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.gestionplus.server.auth.UserAccount;

@RestController
public class AuthSessionController {

	@GetMapping("/api/auth/me")
	public Map<String, String> getCurrentUser(Authentication authentication) {
		UserAccount account = (UserAccount) authentication.getPrincipal();
		return Map.of("userName", account.getUserName());
	}
}
