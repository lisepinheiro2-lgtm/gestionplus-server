package fr.gestionplus.server.account;

import java.util.Optional;
import java.util.UUID;

public interface ActivationIdentityVerifier {

	Optional<UUID> verify(UUID invitationID, UUID userID, String phoneNumber, String proof);
}
