package fr.gestionplus.server.account;

import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.audit.AuditReason;

public class ActivationDeniedException extends AccessDeniedException {

	private final AuditReason reason;

	public ActivationDeniedException(String message, AuditReason reason) {
		super(message);
		this.reason = reason;
	}

	public AuditReason getReason() {
		return reason;
	}
}
