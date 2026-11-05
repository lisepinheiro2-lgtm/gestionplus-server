package fr.gestionplus.server.auth;

import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

@Component
public class AuthenticatedUserProvider {

	public UUID getCurrentUserID() {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount)
				|| !authentication.isAuthenticated())
			throw new AuthenticationCredentialsNotFoundException("Vous ne passerez paaaaas !!!");

		UserAccount account = (UserAccount) authentication.getPrincipal();

		return account.getUserID();
	}
}
