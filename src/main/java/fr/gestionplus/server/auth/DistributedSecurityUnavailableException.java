package fr.gestionplus.server.auth;

import org.springframework.security.authentication.AuthenticationServiceException;

public final class DistributedSecurityUnavailableException extends AuthenticationServiceException {

	public DistributedSecurityUnavailableException() {
		super("Le service de sécurité partagé est temporairement indisponible.");
	}
}
