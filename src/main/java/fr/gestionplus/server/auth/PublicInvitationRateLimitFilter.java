package fr.gestionplus.server.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.gestionplus.server.api.AccountActivationRoutes;
import fr.gestionplus.server.api.HttpApiError;
import fr.gestionplus.server.audit.AuditReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

final class PublicInvitationRateLimitFilter extends OncePerRequestFilter {

	private static final String SCOPE = "invitation-activation";
	private static final int LIMIT = 30;
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private final RequestRateLimiter rateLimiter;
	private final HttpSecurityAudit audit;

	PublicInvitationRateLimitFilter(HttpSecurityAudit audit, RequestRateLimiter rateLimiter) {
		this.audit = Objects.requireNonNull(audit);
		this.rateLimiter = Objects.requireNonNull(rateLimiter);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
			throws ServletException, IOException {

		if (!isPublicActivationPost(request)) {
			filterChain.doFilter(request, response);
			return;
		}

		RequestRateLimiter.Decision decision = rateLimiter.acquire(SCOPE, request.getRemoteAddr(), LIMIT, WINDOW);

		if (!decision.allowed()) {
			audit.refused(request, null, AuditReason.RATE_LIMITED);
			response.setHeader("Retry-After", String.valueOf(decision.retryAfter().getSeconds()));
			HttpApiError.TOO_MANY_REQUESTS.write(response);
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean isPublicActivationPost(HttpServletRequest request) {

		String uri = request.getRequestURI().substring(request.getContextPath().length());
		String activationPattern = AccountActivationRoutes.ACTIVATION_PATH.replace("{invitationID}", "*");
		String smsPattern = AccountActivationRoutes.SMS_CHALLENGE_PATH.replace("{invitationID}", "*");
		AntPathMatcher matcher = new AntPathMatcher();

		return "POST".equals(request.getMethod())
				&& (matcher.match(smsPattern, uri) || matcher.match(activationPattern, uri));
	}
}
