package fr.gestionplus.server.auth;

import java.io.IOException;
import java.util.Map;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.gestionplus.server.api.HttpApiError;
import fr.gestionplus.server.audit.AuditReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class HttpRequestPolicyFilter extends OncePerRequestFilter {

	private final HttpSecurityAudit audit;
	private final boolean requireHttps;
	private final long maxBodyBytes;

	HttpRequestPolicyFilter(HttpSecurityAudit audit, boolean requireHttps, long maxBodyBytes) {
		this.audit = audit;
		this.requireHttps = requireHttps;

		if (maxBodyBytes <= 0) {
			throw new IllegalArgumentException("La taille maximale du corps HTTP doit être positive.");
		}

		this.maxBodyBytes = maxBodyBytes;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		if (requireHttps && !request.isSecure()) {
			refuse(request, response, HttpApiError.HTTPS_REQUIRED, AuditReason.INSECURE_TRANSPORT);
			return;
		}

		HttpApiError error = request.getContentLengthLong() > maxBodyBytes ? HttpApiError.PAYLOAD_TOO_LARGE : null;

		if (error == null && "POST".equals(request.getMethod())
				&& "/login".equals(request.getRequestURI().substring(request.getContextPath().length()))) {
			error = validateLogin(request);
		}

		if (error != null) {
			refuse(request, response, error, AuditReason.INVALID_INPUT);
			return;
		}

		chain.doFilter(request, response);
	}

	private HttpApiError validateLogin(HttpServletRequest request) {

		if (request.getQueryString() != null) {
			return HttpApiError.INVALID_REQUEST;
		}

		try {
			String type = request.getContentType();

			if (type == null) {

				if (request.getContentLengthLong() > 0 || request.getHeader(HttpHeaders.TRANSFER_ENCODING) != null) {
					return HttpApiError.UNSUPPORTED_MEDIA_TYPE;
				}

			} else {
				MediaType supplied = MediaType.parseMediaType(type);

				if (!"application".equals(supplied.getType())
						|| !"x-www-form-urlencoded".equals(supplied.getSubtype())) {
					return HttpApiError.UNSUPPORTED_MEDIA_TYPE;
				}

			}

			Map<String, String[]> fields = request.getParameterMap();

			if (invalidField(fields.get("username"), 255)
					|| invalidField(fields.get("password"), PasswordPolicy.MAX_LENGTH)
					|| invalidField(fields.get("totp"), 6)) {
				return HttpApiError.INVALID_REQUEST;
			}

		} catch (InvalidParameterException failure) {
			return failure.getErrorCode() == 413 ? HttpApiError.PAYLOAD_TOO_LARGE : HttpApiError.INVALID_REQUEST;
		} catch (IllegalArgumentException failure) {
			return HttpApiError.INVALID_REQUEST;
		}

		return null;
	}

	private boolean invalidField(String[] values, int maxLength) {
		return values != null && (values.length != 1 || values[0].length() > maxLength);
	}

	private void refuse(HttpServletRequest request, HttpServletResponse response, HttpApiError error,
			AuditReason reason)
			throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		audit.refused(request, authentication == null ? null : authentication.getPrincipal(), reason);
		error.write(response);
	}
}
