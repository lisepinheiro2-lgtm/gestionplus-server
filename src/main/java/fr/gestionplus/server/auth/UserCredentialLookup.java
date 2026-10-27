package fr.gestionplus.server.auth;

import java.util.Optional;
import java.util.UUID;

public interface UserCredentialLookup {

	Optional<UserCredential> findByUserId(UUID userId);
}
