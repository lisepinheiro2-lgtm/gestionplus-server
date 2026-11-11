package fr.gestionplus.server.auth;

import java.io.IOException;
import java.util.Map;

import org.apache.tomcat.util.http.InvalidParameterException;
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
	private final int maxBodyBytes;
	private final int maxParameters;

	HttpRequestPolicyFilter(HttpSecurityAudit audit, boolean requireHttps, long maxBodyBytes) {
		this(audit, requireHttps, maxBodyBytes, 32);
	}

	HttpRequestPolicyFilter(HttpSecurityAudit audit, boolean requireHttps, long maxBodyBytes, int maxParameters) {
		this.audit = audit;
		this.requireHttps = requireHttps;

		if (maxBodyBytes <= 0 || maxBodyBytes >= Integer.MAX_VALUE || maxParameters < 1) {
			throw new IllegalArgumentException("Limites HTTP invalides.");
		}

		this.maxBodyBytes = (int) maxBodyBytes;
		this.maxParameters = maxParameters;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		if (requireHttps && !request.isSecure()) {
			refuse(request, response, HttpApiError.HTTPS_REQUIRED, AuditReason.INSECURE_TRANSPORT);
			return;
		}

		if (request.getContentLengthLong() > maxBodyBytes) {
			refuse(request, response, HttpApiError.PAYLOAD_TOO_LARGE, AuditReason.INVALID_INPUT);
			return;
		}

		BufferedHttpRequest buffered;

		try {
			buffered = new BufferedHttpRequest(request, maxBodyBytes, maxParameters);
		} catch (InvalidParameterException failure) {
			HttpApiError error = failure.getErrorCode() == 413 ? HttpApiError.PAYLOAD_TOO_LARGE
					: HttpApiError.INVALID_REQUEST;
			refuse(request, response, error, AuditReason.INVALID_INPUT);
			return;
		} catch (IllegalArgumentException | IOException failure) {
			refuse(request, response, HttpApiError.INVALID_REQUEST, AuditReason.INVALID_INPUT);
			return;
		}

		if ("POST".equals(request.getMethod())
				&& "/login".equals(request.getRequestURI().substring(request.getContextPath().length()))) {
			HttpApiError error = validateLogin(buffered);

			if (error != null) {
				refuse(request, response, error, AuditReason.INVALID_INPUT);
				return;
			}

		}

		chain.doFilter(buffered, response);
	}

	private HttpApiError validateLogin(BufferedHttpRequest request) {

		if (request.getQueryString() != null) {
			return HttpApiError.INVALID_REQUEST;
		}

		String type = request.getContentType();

		if (type == null && request.hasBody()) {
			return HttpApiError.UNSUPPORTED_MEDIA_TYPE;
		}

		if (type != null) {
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

		return null;
	}

	private boolean invalidField(String[] values, int maxLength) {
		return values != null && (values.length != 1 || values[0].length() > maxLength);
	}

	private void refuse(HttpServletRequest request, HttpServletResponse response, HttpApiError error,
			AuditReason reason)
			throws IOException {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		audit.refused(request,
				authentication != null && authentication.isAuthenticated() ? authentication.getPrincipal() : null,
				reason);
		error.write(response);
	}
}
