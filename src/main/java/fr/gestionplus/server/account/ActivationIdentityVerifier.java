package fr.gestionplus.server.account;

import java.util.UUID;

public interface ActivationIdentityVerifier {

	boolean verify(UUID invitationID, UUID userID, String phoneNumber, String proof);
}
