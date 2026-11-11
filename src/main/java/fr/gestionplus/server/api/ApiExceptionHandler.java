package fr.gestionplus.server.api;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.auth.HttpSecurityAudit;
import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
class ApiExceptionHandler extends ResponseEntityExceptionHandler {

	private static final Logger LOGGER = LoggerFactory.getLogger(ApiExceptionHandler.class);

	private final HttpSecurityAudit audit;

	ApiExceptionHandler(AuditService auditService) {
		this.audit = new HttpSecurityAudit(auditService);
	}

	@ExceptionHandler(AuthenticationException.class)
	ResponseEntity<Object> unauthenticated(HttpServletRequest request) {
		audit.refused(request, null, AuditReason.AUTHENTICATION_REQUIRED);
		return response(HttpApiError.AUTHENTICATION_REQUIRED);
	}

	@ExceptionHandler(AccessDeniedException.class)
	ResponseEntity<Object> denied(AccessDeniedException failure, HttpServletRequest request,
			Authentication authentication) {

		if (authentication == null || !authentication.isAuthenticated()
				|| authentication instanceof AnonymousAuthenticationToken) {
			audit.refused(request, null, AuditReason.AUTHENTICATION_REQUIRED);
			return response(HttpApiError.AUTHENTICATION_REQUIRED);
		}

		AuditReason reason = failure instanceof AuditedAccessDeniedException denied ? denied.getReason()
				: AuditReason.ACCESS_NOT_AUTHORIZED;
		audit.refused(request, authentication.getPrincipal(), reason);
		return response(HttpApiError.fromAccessDenied(failure));
	}

	@ExceptionHandler(IllegalArgumentException.class)
	ResponseEntity<Object> invalidRequest() {
		return response(HttpApiError.INVALID_REQUEST);
	}

	@ExceptionHandler(ConcurrencyFailureException.class)
	ResponseEntity<Object> conflict() {
		return response(HttpApiError.CONFLICT);
	}

	@ExceptionHandler({ DataAccessResourceFailureException.class, CannotCreateTransactionException.class })
	ResponseEntity<Object> unavailable(Exception failure) {
		LOGGER.warn("Dépendance HTTP indisponible ({})", failure.getClass().getSimpleName());
		return response(HttpApiError.SERVICE_UNAVAILABLE);
	}

	@ExceptionHandler(Exception.class)
	ResponseEntity<Object> internalError(Exception failure) {
		LOGGER.error("Échec inattendu d'une requête HTTP ({})", failure.getClass().getSimpleName());
		return response(HttpApiError.INTERNAL_ERROR);
	}

	@Override
	protected ResponseEntity<Object> handleExceptionInternal(Exception failure, Object body, HttpHeaders headers,
			HttpStatusCode status, WebRequest request) {
		HttpHeaders responseHeaders = new HttpHeaders();
		responseHeaders.addAll(headers);
		responseHeaders.setContentType(MediaType.APPLICATION_JSON);
		responseHeaders.setCacheControl("no-store");
		return super.handleExceptionInternal(failure, HttpApiError.fromStatus(status.value()).body(),
				responseHeaders, status, request);
	}

	private ResponseEntity<Object> response(HttpApiError error) {
		return ResponseEntity.status(error.status()).contentType(MediaType.APPLICATION_JSON)
				.header(HttpHeaders.CACHE_CONTROL, "no-store").body(error.body());
	}
}
