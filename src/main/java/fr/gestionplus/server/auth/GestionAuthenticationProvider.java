package fr.gestionplus.server.auth;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

@Component
public class GestionAuthenticationProvider implements AuthenticationProvider {

	private final LoginService loginService;

	public GestionAuthenticationProvider(LoginService loginService) {
		this.loginService = Objects.requireNonNull(loginService);
	}

	@Override
	public @Nullable Authentication authenticate(Authentication authentication) throws AuthenticationException {

		String userName = authentication.getName();
		Object credentials = authentication.getCredentials();

		if (!(credentials instanceof String))
			throw new BadCredentialsException("Identifiants incorrects");

		String rawPassword = (String) credentials;

		Optional<UserAccount> account = loginService.authenticate(userName, rawPassword);

		if (account.isEmpty())
			throw new BadCredentialsException("Identifiants incorrects");

		UserAccount authenticatedAccount = account.get();

		return UsernamePasswordAuthenticationToken.authenticated(authenticatedAccount, null, List.of());
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
