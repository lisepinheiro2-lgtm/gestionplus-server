package fr.gestionplus.server.api;

import java.io.IOException;
import java.util.Map;

import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import jakarta.servlet.http.HttpServletResponse;

public enum HttpApiError {

	INVALID_REQUEST(400),
	AUTHENTICATION_REQUIRED(401),
	FORBIDDEN(403),
	HTTPS_REQUIRED(403),
	NOT_FOUND(404),
	METHOD_NOT_ALLOWED(405),
	NOT_ACCEPTABLE(406),
	CONFLICT(409),
	PAYLOAD_TOO_LARGE(413),
	UNSUPPORTED_MEDIA_TYPE(415),
	INTERNAL_ERROR(500),
	SERVICE_UNAVAILABLE(503);

	private final int status;

	HttpApiError(int status) {
		this.status = status;
	}

	int status() {
		return status;
	}

	Map<String, String> body() {
		return Map.of("code", name());
	}

	public void write(HttpServletResponse response) throws IOException {
		write(response, status);
	}

	void write(HttpServletResponse response, int statusCode) throws IOException {
		response.setStatus(statusCode);
		response.setHeader("Cache-Control", "no-store");
		response.setContentType("application/json");
		response.getWriter().write("{\"code\":\"" + name() + "\"}");
	}

	public static HttpApiError fromAccessDenied(AccessDeniedException failure) {

		if (failure instanceof AuditedAccessDeniedException stale && stale.getReason() == AuditReason.STALE_SESSION) {
			return AUTHENTICATION_REQUIRED;
		}

		if (failure instanceof AuditedAccessDeniedException denied
				&& (denied.getReason() == AuditReason.OUTSIDE_TENANT
						|| denied.getReason() == AuditReason.OUTSIDE_STRUCTURE)) {
			return NOT_FOUND;
		}

		return FORBIDDEN;
	}

	static HttpApiError fromStatus(int status) {
		return switch (status) {
		case 401 -> AUTHENTICATION_REQUIRED;
		case 403 -> FORBIDDEN;
		case 404 -> NOT_FOUND;
		case 405 -> METHOD_NOT_ALLOWED;
		case 406 -> NOT_ACCEPTABLE;
		case 409 -> CONFLICT;
		case 413 -> PAYLOAD_TOO_LARGE;
		case 415 -> UNSUPPORTED_MEDIA_TYPE;
		case 503 -> SERVICE_UNAVAILABLE;
		default -> status < 500 ? INVALID_REQUEST : INTERNAL_ERROR;
		};
	}
}
