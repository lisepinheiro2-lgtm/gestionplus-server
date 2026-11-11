package fr.gestionplus.server.auth;

import java.util.UUID;

public interface MfaEnrollmentVerifier {

	boolean consumeVerifiedIdentity(UUID userID, long sessionVersion, String proof);
}
