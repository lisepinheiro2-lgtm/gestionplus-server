package fr.gestionplus.server.auth;

import java.util.Objects;

public class PasswordPolicy {

	static final int MAX_LENGTH = 128;

	private final CompromisedPasswordChecker checker;

	public PasswordPolicy(CompromisedPasswordChecker checker) {
		this.checker = Objects.requireNonNull(checker);
	}

	public void validate(String rawPassword) {

		if (rawPassword == null) {
			throw new IllegalArgumentException("Le mot de passe ne peut pas être null.");
		}

		if (rawPassword.isBlank()) {
			throw new IllegalArgumentException("Le mot de passe de peut être vide ou uniquement constitué d'espaces.");
		}

		if (rawPassword.length() < 12) {
			throw new IllegalArgumentException("Le mot de passe ne peut pas contenir moins de douze caractères.");
		}

		if (rawPassword.length() > MAX_LENGTH) {
			throw new IllegalArgumentException("Le mot de passe ne peut pas dépasser 128 caractères.");
		}

		if (checker.isCompromised(rawPassword)) {
			throw new IllegalArgumentException("Ce mot de passe est compromis, veuillez en choisir un autre.");
		}

	}
}
