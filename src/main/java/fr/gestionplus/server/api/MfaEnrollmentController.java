package fr.gestionplus.server.api;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.gestionplus.server.account.ActivationDeniedException;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AccountMfaStore.EnrollmentProof;
import fr.gestionplus.server.auth.EnrollmentPrincipal;
import fr.gestionplus.server.auth.UserAccount;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

@RestController
public class MfaEnrollmentController {

	private final AccountMfaService accountMfaService;
	private final SecurityContextRepository securityContextRepository;

	public MfaEnrollmentController(AccountMfaService accountMfaService,
			SecurityContextRepository securityContextRepository) {
		this.accountMfaService = Objects.requireNonNull(accountMfaService);
		this.securityContextRepository = Objects.requireNonNull(securityContextRepository);
	}

	@PostMapping(MfaEnrollmentRoutes.ENROLLMENT_PATH)
	public AccountMfaService.Enrollment begin(Authentication authentication,
			@RequestBody EnrollmentProofRequest request) {

		if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount)
				|| !authentication.isAuthenticated()) {
			throw new AuthenticationCredentialsNotFoundException("Compte invalide.");
		}

		UserAccount principal = (UserAccount) authentication.getPrincipal();
		return accountMfaService.beginEnrollment(principal.getUserID(), request.proof());
	}

	@PostMapping(MfaEnrollmentRoutes.ENROLLMENT_COMPLETE_PATH)
	public AccountMfaService.RecoveryCodes complete(Authentication authentication,
			@RequestBody CompleteRequest request) {

		if (authentication == null || !(authentication.getPrincipal() instanceof UserAccount)
				|| !authentication.isAuthenticated()) {
			throw new AuthenticationCredentialsNotFoundException("Compte invalide.");
		}

		UserAccount principal = (UserAccount) authentication.getPrincipal();
		return accountMfaService.completeEnrollment(principal.getUserID(), request.enrollmentID(), request.code());
	}

	@PostMapping(MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH)
	public ResponseEntity<Void> openSession(HttpServletRequest request, HttpServletResponse response,
			@RequestBody EnrollmentProofRequest proof) {

		String proofText = proof.proof();
		Optional<EnrollmentProof> optionalProof = accountMfaService.openContext(proofText);

		if (optionalProof.isEmpty()) {
			throw new ActivationDeniedException("Preuve invalide.", AuditReason.ACCESS_NOT_AUTHORIZED);
		}

		HttpSession session = request.getSession(true);
		request.changeSessionId();

		EnrollmentProof enrollmentProof = optionalProof.get();

		UUID userID = enrollmentProof.userID();
		long sessionVersion = enrollmentProof.sessionVersion();
		EnrollmentPrincipal principal = new EnrollmentPrincipal(userID, sessionVersion);

		UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken
				.authenticated(principal, null, List.of(new SimpleGrantedAuthority("ROLE_MFA_ENROLLMENT")));

		SecurityContext context = SecurityContextHolder.createEmptyContext();
		context.setAuthentication(result);
		SecurityContextHolder.setContext(context);
		session.setMaxInactiveInterval(10 * 60);

		securityContextRepository.saveContext(context, request, response);

		return ResponseEntity.noContent().build();
	}

	record CompleteRequest(UUID enrollmentID, String code) {
	}

	record EnrollmentProofRequest(String proof) {
	}
}
