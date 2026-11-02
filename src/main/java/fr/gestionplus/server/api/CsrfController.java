package fr.gestionplus.server.api;

import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class CsrfController {

	@GetMapping("/api/auth/csrf")
	public CsrfToken getCsrfToken(CsrfToken csrfToken) {
		return csrfToken;
	}
}
