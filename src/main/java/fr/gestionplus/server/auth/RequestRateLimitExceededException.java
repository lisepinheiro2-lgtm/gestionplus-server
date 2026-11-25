package fr.gestionplus.server.auth;

import java.time.Duration;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;

public final class RequestRateLimitExceededException extends AuditedAccessDeniedException {
	private final Duration retryAfter;

	public RequestRateLimitExceededException(Duration retryAfter) {
		super("Trop de demandes. Réessayez plus tard.", AuditReason.RATE_LIMITED);
		this.retryAfter = retryAfter;
	}

	public long retryAfterSeconds() {
		return Math.max(1, retryAfter.getSeconds() + (retryAfter.getNano() == 0 ? 0 : 1));
	}
}
