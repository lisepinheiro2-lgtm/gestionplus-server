package fr.gestionplus.server.audit;

import org.springframework.security.access.AccessDeniedException;

public class AuditedAccessDeniedException extends AccessDeniedException {

	private final AuditReason reason;

	public AuditedAccessDeniedException(String message, AuditReason reason) {
		super(message);
		this.reason = reason;
	}

	public AuditReason getReason() {
		return reason;
	}
}
