package fr.gestionplus.server.auth;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

import org.springframework.http.HttpMethod;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
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
	private static final PathPatternRequestMatcher ACTIVATION_POST = PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.POST, AccountActivationRoutes.ACTIVATION_PATH);
	private static final PathPatternRequestMatcher SMS_CHALLENGE_POST = PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.POST, AccountActivationRoutes.SMS_CHALLENGE_PATH);
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
			Duration retryAfter = decision.retryAfter();
			long seconds = Math.max(1, retryAfter.getSeconds() + (retryAfter.getNano() == 0 ? 0 : 1));
			response.setHeader("Retry-After", Long.toString(seconds));
			HttpApiError.TOO_MANY_REQUESTS.write(response);
			return;
		}

		filterChain.doFilter(request, response);
	}

	private boolean isPublicActivationPost(HttpServletRequest request) {
		return ACTIVATION_POST.matches(request) || SMS_CHALLENGE_POST.matches(request);
	}
}
