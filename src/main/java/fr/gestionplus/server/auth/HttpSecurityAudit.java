package fr.gestionplus.server.auth;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import jakarta.servlet.http.HttpServletRequest;

public final class HttpSecurityAudit {

	private static final Logger LOGGER = LoggerFactory.getLogger(HttpSecurityAudit.class);
	private final AuditService auditService;

	public HttpSecurityAudit(AuditService auditService) {
		this.auditService = auditService;
	}

	void loginSucceeded(HttpServletRequest request, Authentication authentication) {
		UUID actorId = actor(authentication.getPrincipal());
		auditService.recordForActor(AuditAction.AUTH_LOGIN, AuditResult.SUCCESS, actorId, request.getRemoteAddr(),
				targets(actorId), AuditMetadata.empty());
	}

	void loginFailed(HttpServletRequest request, AuthenticationException failure) {
		UUID targetId = null;
		AuditReason reason = AuditReason.INVALID_CREDENTIALS;

		if (failure instanceof GestionAuthenticationProvider.RejectedLogin rejected) {
			targetId = rejected.targetId();
			reason = rejected.reason();
		}

		auditService.recordForActor(AuditAction.AUTH_LOGIN, AuditResult.FAILURE, null, request.getRemoteAddr(),
				targets(targetId), AuditMetadata.because(reason));

		if (failure instanceof GestionAuthenticationProvider.RejectedLogin rejected && rejected.restrictionStarted()) {
			auditService.recordForActor(AuditAction.AUTH_LOGIN_RESTRICTION, AuditResult.DENIED, null,
					request.getRemoteAddr(), targets(targetId), AuditMetadata.because(AuditReason.RATE_LIMITED));
		}

	}

	void logout(HttpServletRequest request, Authentication authentication) {
		UUID actorId = authentication == null ? null : actor(authentication.getPrincipal());

		if (actorId == null) {
			return;
		}

		try {
			auditService.recordForActor(AuditAction.AUTH_LOGOUT, AuditResult.SUCCESS, actorId, request.getRemoteAddr(),
					targets(actorId), AuditMetadata.empty());
		} catch (RuntimeException failure) {
			LOGGER.error("Déconnexion effective, audit indisponible ({})", failure.getClass().getSimpleName());
		}

	}

	public void refused(HttpServletRequest request, Object principal, AuditReason reason) {
		UUID actorId = actor(principal);
		auditService.recordForActor(AuditAction.AUTH_ACCESS_ATTEMPT, AuditResult.DENIED, actorId,
				request.getRemoteAddr(),
				targets(actorId), AuditMetadata.because(reason));
	}

	private UUID actor(Object principal) {
		return principal instanceof UserAccount account ? account.getUserID() : null;
	}

	private Set<AuditTarget> targets(UUID accountId) {
		return accountId == null ? Set.of() : Set.of(new AuditTarget(AuditTargetType.ACCOUNT, accountId));
	}
}
