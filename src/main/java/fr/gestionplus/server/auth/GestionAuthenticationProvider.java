package fr.gestionplus.server.auth;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.auth.LoginService.LoginAttempt;

@Component
public class GestionAuthenticationProvider implements AuthenticationProvider {

	private final LoginService loginService;
	private final AccountMfaService mfa;
	private final LoginAttemptLimiter limiter;

	public GestionAuthenticationProvider(LoginService loginService, AccountMfaService mfa,
			LoginAttemptLimiter limiter) {
		this.loginService = Objects.requireNonNull(loginService);
		this.mfa = Objects.requireNonNull(mfa);
		this.limiter = Objects.requireNonNull(limiter);
	}

	@Override
	public @Nullable Authentication authenticate(Authentication authentication) throws AuthenticationException {

		String userName = authentication.getName();
		Object credentials = authentication.getCredentials();

		if (!(credentials instanceof String rawPassword)) {
			throw new RejectedLogin(null, AuditReason.INVALID_CREDENTIALS);
		}

		LoginDetails details = authentication.getDetails() instanceof LoginDetails supplied ? supplied
				: new LoginDetails(null, null);
		LoginAttemptLimiter.Attempt reservation = limiter.begin(userName, details.sourceAddress());

		if (reservation == null) {
			throw new RejectedLogin(null, AuditReason.RATE_LIMITED);
		}

		RejectedLogin rejection = null;

		try {
			LoginAttempt attempt = loginService.evaluate(userName, rawPassword);

			if (attempt.account() == null) {
				throw new RejectedLogin(attempt.targetId(), attempt.reason());
			}

			AccountMfaService.Verification verification = mfa.verifyForLogin(attempt.account(), details.totpCode());

			if (verification != AccountMfaService.Verification.NOT_REQUIRED
					&& verification != AccountMfaService.Verification.VERIFIED) {
				throw new RejectedLogin(attempt.targetId(), AuditReason.MFA_REQUIRED);
			}

			UsernamePasswordAuthenticationToken result = UsernamePasswordAuthenticationToken
					.authenticated(attempt.account(), null, List.of());
			result.setDetails(new LoginAssurance(verification == AccountMfaService.Verification.VERIFIED));
			return result;

		} catch (RejectedLogin failure) {
			rejection = failure;
			throw failure;

		} catch (DataAccessException | IllegalStateException failure) {
			rejection = new RejectedLogin(null, AuditReason.OPERATION_FAILED);
			throw rejection;

		} finally {
			boolean restricted = limiter.finish(reservation,
					rejection != null && rejection.reason() != AuditReason.OPERATION_FAILED,
					rejection != null && rejection.targetId() != null);

			if (rejection != null) {
				rejection.restrictionStarted = restricted;
			}

		}

	}

	static final class RejectedLogin extends BadCredentialsException {

		private final UUID targetId;
		private final AuditReason reason;
		private boolean restrictionStarted;

		RejectedLogin(UUID targetId, AuditReason reason) {
			super("Identifiants incorrects");
			this.targetId = targetId;
			this.reason = reason;
		}

		UUID targetId() {
			return targetId;
		}

		boolean restrictionStarted() {
			return restrictionStarted;
		}

		AuditReason reason() {
			return reason;
		}
	}

	public record LoginDetails(String sourceAddress, String totpCode) {
		@Override
		public String toString() {
			return "LoginDetails[protected]";
		}
	}

	public record LoginAssurance(boolean mfaVerified) implements java.io.Serializable {
	}

	@Override
	public boolean supports(Class<?> authentication) {
		return UsernamePasswordAuthenticationToken.class.isAssignableFrom(authentication);
	}

}
