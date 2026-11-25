package fr.gestionplus.server.auth;

import java.util.UUID;

import org.springframework.stereotype.Component;

@Component
public class CurrentPasswordVerifier {

	private final UserCredentialLookup credentials;
	private final CredentialService passwords;

	public CurrentPasswordVerifier(UserCredentialLookup credentials, CredentialService passwords) {
		this.credentials = credentials;
		this.passwords = passwords;
	}

	public boolean matches(UUID userID, String rawPassword) {
		return credentials.findByUserId(userID)
				.filter(credential -> userID.equals(credential.getUserId()))
				.filter(credential -> passwords.verifyPassword(credential, rawPassword))
				.isPresent();
	}
}
