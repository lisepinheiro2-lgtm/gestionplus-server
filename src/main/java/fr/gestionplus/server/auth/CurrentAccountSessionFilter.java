package fr.gestionplus.server.auth;

import java.io.IOException;
import java.time.Instant;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.api.HttpApiError;
import fr.gestionplus.server.audit.AuditReason;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

final class CurrentAccountSessionFilter extends OncePerRequestFilter {

	private final AccountStore accountStore;
	private final HttpSecurityAudit audit;
	private final SessionLifetime lifetime;
	private final AccountMfaService mfa;
	private final SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

	CurrentAccountSessionFilter(AccountStore accountStore, HttpSecurityAudit audit, SessionLifetime lifetime,
			AccountMfaService mfa) {
		this.accountStore = accountStore;
		this.audit = audit;
		this.lifetime = lifetime;
		this.mfa = mfa;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		Instant requestStarted = lifetime.now();
		HttpSession session = request.getSession(false);

		if (authentication != null && authentication.getPrincipal() instanceof UserAccount principal) {
			boolean current = accountStore.findById(principal.getUserID())
					.filter(account -> principal.getUserID().equals(account.getUserID()))
					.filter(account -> account.getStatu() == UserAccountStatus.ACTIVE)
					.filter(account -> account.getSessionVersion() == principal.getSessionVersion()).isPresent();

			if (!current) {
				logoutHandler.logout(request, response, authentication);
				audit.refused(request, principal, AuditReason.STALE_SESSION);
				HttpApiError.AUTHENTICATION_REQUIRED.write(response);
				return;
			}

			SessionLifetime.Access access = lifetime.access(session, requestStarted);

			if (access == null) {
				logoutHandler.logout(request, response, authentication);
				audit.refused(request, principal, AuditReason.SESSION_EXPIRED);
				HttpApiError.AUTHENTICATION_REQUIRED.write(response);
				return;
			}

			if (!access.mfaVerified() && mfa.requiresVerification(principal.getUserID())) {
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
