package fr.gestionplus.server.auth;

import java.io.Serializable;
import java.util.UUID;

public record EnrollmentPrincipal(UUID userID, long sessionVersion) implements Serializable {
}
