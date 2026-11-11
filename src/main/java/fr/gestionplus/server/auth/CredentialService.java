package fr.gestionplus.server.auth;

import java.util.Objects;
import java.util.UUID;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class CredentialService {

	private final PasswordEncoder passwordEncoder;
	private final PasswordPolicy policy;
	private final String dummyPasswordHash;

	public CredentialService(PasswordEncoder passwordEncoder, PasswordPolicy policy) {
		this.passwordEncoder = Objects.requireNonNull(passwordEncoder);
		this.policy = Objects.requireNonNull(policy);
		dummyPasswordHash = passwordEncoder.encode(UUID.randomUUID().toString());
	}

	public UserCredential createCredential(UUID userId, String rawPassword) {

		if (userId == null) {
			throw new NullPointerException("L'ID utilisateur ne peut pas être null");
		}

		policy.validate(rawPassword);

		String passwordHash = passwordEncoder.encode(rawPassword);
		return new UserCredential(userId, passwordHash);
	}

	void verifyDummyPassword(String rawPassword) {
		passwordEncoder.matches(rawPassword, dummyPasswordHash);
	}

	public boolean verifyPassword(UserCredential credential, String rawPassword) {

		if (credential == null || rawPassword == null || rawPassword.isBlank()) {
			return false;
		}

		return passwordEncoder.matches(rawPassword, credential.getPasswordHash());
	}

}
