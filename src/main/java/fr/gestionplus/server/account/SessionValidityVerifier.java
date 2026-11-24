package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface SessionValidityVerifier {

	boolean isValid(UUID userID, long sessionVersion, String sessionIDHash);

	Optional<Instant> validateTogether(UUID firstUserID, long firstSessionVersion, String firstSessionIDHash,
			UUID secondUserID, long secondSessionVersion, String secondSessionIDHash);
}
