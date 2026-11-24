package fr.gestionplus.server.auth;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpMethod;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.api.HttpApiError;
import fr.gestionplus.server.api.MfaEnrollmentRoutes;
import fr.gestionplus.server.audit.AuditReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

final class CurrentAccountSessionFilter extends OncePerRequestFilter {

	private static final PathPatternRequestMatcher CSRF_GET = PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.GET, "/api/auth/csrf");
	private static final PathPatternRequestMatcher ENROLLMENT_POST = PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.POST, MfaEnrollmentRoutes.ENROLLMENT_PATH);
	private static final PathPatternRequestMatcher COMPLETE_POST = PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.POST, MfaEnrollmentRoutes.ENROLLMENT_COMPLETE_PATH);
	private static final PathPatternRequestMatcher LOGOUT_POST = PathPatternRequestMatcher.withDefaults()
			.matcher(HttpMethod.POST, "/logout");
	private static final int MAX_ENROLLMENT_SUBMISSIONS = 5;
	private final AccountStore accountStore;
	private final HttpSecurityAudit audit;
	private final SessionLifetime lifetime;
	private final AccountMfaService mfa;
	private final RequestRateLimiter rateLimiter;
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	CurrentAccountSessionFilter(AccountStore accountStore, HttpSecurityAudit audit, SessionLifetime lifetime,
			AccountMfaService mfa, RequestRateLimiter rateLimiter) {
		this.accountStore = accountStore;
		this.audit = audit;
		this.lifetime = lifetime;
		this.mfa = mfa;
		this.rateLimiter = rateLimiter;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Instant requestStarted = null;
		HttpSession session = request.getSession(false);

		if (authentication != null && authentication.getPrincipal() instanceof EnrollmentPrincipal principal) {
			boolean current = accountStore.findById(principal.userID())
					.filter(account -> principal.userID().equals(account.getUserID())
							&& account.getStatu() == UserAccountStatus.ACTIVE
							&& account.getSessionVersion() == principal.sessionVersion())
					.isPresent();
			Instant now = lifetime.now();

			if (!current || principal.isExpired(now) || session == null) {
				logoutHandler.logout(request, response, authentication);
				audit.refused(request, principal, !current ? AuditReason.STALE_SESSION : AuditReason.SESSION_EXPIRED);
				HttpApiError.AUTHENTICATION_REQUIRED.write(response);
				return;
			}

			if (!CSRF_GET.matches(request) && !ENROLLMENT_POST.matches(request)
					&& !COMPLETE_POST.matches(request) && !LOGOUT_POST.matches(request)) {
				audit.refused(request, principal, AuditReason.ACCESS_NOT_AUTHORIZED);
				HttpApiError.FORBIDDEN.write(response);
				return;
			}

			if (COMPLETE_POST.matches(request)) {
				RequestRateLimiter.Decision decision = rateLimiter.acquireForAccount("mfa-enrollment",
						principal.userID(),
						MAX_ENROLLMENT_SUBMISSIONS, EnrollmentPrincipal.VALIDITY);

				if (!decision.allowed()) {
					audit.refused(request, principal, AuditReason.RATE_LIMITED);
					Duration delay = decision.retryAfter();
					long seconds = Math.max(1, delay.getSeconds() + (delay.getNano() == 0 ? 0 : 1));
					response.setHeader("Retry-After", Long.toString(seconds));
					HttpApiError.TOO_MANY_REQUESTS.write(response);
					return;
				}

			}

			chain.doFilter(request, response);
			return;
		}

		if (authentication != null && authentication.getPrincipal() instanceof UserAccount principal) {
			requestStarted = lifetime.now();
			boolean current = accountStore.findById(principal.getUserID())
					.filter(account -> principal.getUserID().equals(account.getUserID()))
					.filter(account -> account.getStatu() == UserAccountStatus.ACTIVE)
					.filter(account -> account.getSessionVersion() == principal.getSessionVersion()).isPresent();

			if (!current) {
				lifetime.end(session);
				logoutHandler.logout(request, response, authentication);
				audit.refused(request, principal, AuditReason.STALE_SESSION);
				HttpApiError.AUTHENTICATION_REQUIRED.write(response);
				return;
			}

			SessionLifetime.Access access = lifetime.access(session, requestStarted);

			if (access == null) {
				lifetime.end(session);
				logoutHandler.logout(request, response, authentication);
				audit.refused(request, principal, AuditReason.SESSION_EXPIRED);
				HttpApiError.AUTHENTICATION_REQUIRED.write(response);
				return;
			}

			if (!access.mfaVerified() && mfa.requiresVerification(principal.getUserID())) {
				lifetime.end(session);
				logoutHandler.logout(request, response, authentication);
				audit.refused(request, principal, AuditReason.MFA_REQUIRED);
				HttpApiError.AUTHENTICATION_REQUIRED.write(response);
				return;
			}

			request.setAttribute(SessionLifetime.REQUEST_EXPIRATION, access.expiration());
		}

		chain.doFilter(request, response);

		String path = request.getRequestURI().substring(request.getContextPath().length());

		if (session != null && request.getAttribute(SessionLifetime.REQUEST_EXPIRATION) != null
				&& response.getStatus() >= 200 && response.getStatus() < 300
				&& !SessionLifetime.STATUS_PATH.equals(path) && !"/api/auth/csrf".equals(path)) {
			lifetime.touch(session, requestStarted);
		}

	}

}
