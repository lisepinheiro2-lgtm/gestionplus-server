package fr.gestionplus.server.auth;

import java.util.Objects;
import java.util.UUID;

public class UserCredential {

	private final UUID userId;
	private final String passwordHash;

	public UserCredential(UUID userId, String passwordHash) {

		this.userId = Objects.requireNonNull(userId, "L'UUID de l'utilisateur ne peut pas être null");
		this.passwordHash = Objects.requireNonNull(passwordHash, "L'empreinte du mot de passe ne peut pas être null");

		if (this.passwordHash.isBlank()) {
			throw new IllegalArgumentException("L'empreinte du mot de passe ne peut pas être vide");
		}
	}

	public String getPasswordHash() {
		return passwordHash;
	}

	public UUID getUserId() {
		return userId;
	}
}
