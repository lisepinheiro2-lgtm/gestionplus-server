package fr.gestionplus.server.api;

import java.util.Objects;
import java.util.UUID;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.UserAccount;

@RestController
public class MfaEnrollmentController {

	private final AccountMfaService accountMfaService;

	public MfaEnrollmentController(AccountMfaService accountMfaService) {
		this.accountMfaService = Objects.requireNonNull(accountMfaService);
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

	record CompleteRequest(UUID enrollmentID, String code) {
	}

	record EnrollmentProofRequest(String proof) {
	}
}
