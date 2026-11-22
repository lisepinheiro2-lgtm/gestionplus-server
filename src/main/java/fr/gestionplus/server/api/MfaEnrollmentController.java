package fr.gestionplus.server.api;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.gestionplus.server.account.ActivationDeniedException;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.EnrollmentPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
public class MfaEnrollmentController {

	private final AccountMfaService accountMfaService;
	private final SecurityContextRepository securityContextRepository;
	private final CsrfTokenRepository csrfTokenRepository;

	public MfaEnrollmentController(AccountMfaService accountMfaService,
			SecurityContextRepository securityContextRepository, CsrfTokenRepository csrfTokenRepository) {
		this.accountMfaService = Objects.requireNonNull(accountMfaService);
		this.securityContextRepository = Objects.requireNonNull(securityContextRepository);
		this.csrfTokenRepository = Objects.requireNonNull(csrfTokenRepository);
	}

	@PostMapping(MfaEnrollmentRoutes.ENROLLMENT_PATH)
	public AccountMfaService.Enrollment begin(Authentication authentication) {
		return accountMfaService.beginEnrollment(requirePrincipal(authentication));
	}

	@PostMapping(MfaEnrollmentRoutes.ENROLLMENT_COMPLETE_PATH)
	public AccountMfaService.RecoveryCodes complete(Authentication authentication,
			@RequestBody CompleteRequest request, HttpServletRequest servletRequest, HttpServletResponse response) {
		AccountMfaService.RecoveryCodes codes = accountMfaService.completeEnrollment(requirePrincipal(authentication),
				request.enrollmentID(), request.code());
		new SecurityContextLogoutHandler().logout(servletRequest, response, authentication);
		securityContextRepository.saveContext(SecurityContextHolder.createEmptyContext(), servletRequest, response);
		return codes;
	}

	@PostMapping(MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH)
	public ResponseEntity<Void> openSession(HttpServletRequest request, HttpServletResponse response,
			@RequestBody EnrollmentProofRequest proof, Authentication authentication) {
		if (authentication != null && authentication.isAuthenticated()
				&& !(authentication instanceof AnonymousAuthenticationToken)) {
			throw new ActivationDeniedException("Preuve invalide.", AuditReason.ACCESS_NOT_AUTHORIZED);
		}
		EnrollmentPrincipal principal = accountMfaService.openContext(proof.proof()).orElseThrow(
				() -> new ActivationDeniedException("Preuve invalide.", AuditReason.ACCESS_NOT_AUTHORIZED));

		HttpSession session = request.getSession(true);
		request.changeSessionId();

		UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken
				.authenticated(principal, null, List.of(new SimpleGrantedAuthority(EnrollmentPrincipal.AUTHORITY)));

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(result);
		SecurityContextHolder.setContext(context);
		session.setMaxInactiveInterval(Math.toIntExact(EnrollmentPrincipal.VALIDITY.toSeconds()));
		csrfTokenRepository.saveToken(null, request, response);

		securityContextRepository.saveContext(context, request, response);

		return ResponseEntity.noContent().build();
	}

	private EnrollmentPrincipal requirePrincipal(Authentication authentication) {
		if (authentication == null || !authentication.isAuthenticated()
				|| !(authentication.getPrincipal() instanceof EnrollmentPrincipal principal)) {
			throw new AuthenticationCredentialsNotFoundException("Compte invalide.");
		}
		return principal;
	}

	record CompleteRequest(UUID enrollmentID, String code) {
	}

	record EnrollmentProofRequest(String proof) {
	}
}
