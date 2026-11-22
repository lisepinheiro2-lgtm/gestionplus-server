package fr.gestionplus.server.auth;

import java.io.Serializable;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

public record EnrollmentPrincipal(UUID userID, long sessionVersion, Instant expiresAt) implements Serializable {

	public static final Duration VALIDITY = Duration.ofMinutes(10);
	public static final String AUTHORITY = "ROLE_MFA_ENROLLMENT";

	public EnrollmentPrincipal {
		Objects.requireNonNull(userID);
		Objects.requireNonNull(expiresAt);
		if (sessionVersion < 0) {
			throw new IllegalArgumentException("La version de sécurité est invalide.");
		}
	}

	public boolean isExpired(Instant now) {
		return !now.isBefore(expiresAt);
	}
}
