package fr.gestionplus.server.auth;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.stereotype.Component;

import fr.gestionplus.server.account.SessionValidityVerifier;

@Component
public class SpringSessionValidityVerifier implements SessionValidityVerifier {

	private final FindByIndexNameSessionRepository<? extends Session> sessions;
	private final SessionActivityStore activity;

	public SpringSessionValidityVerifier(FindByIndexNameSessionRepository<? extends Session> sessions,
			SessionActivityStore activity) {
		this.sessions = Objects.requireNonNull(sessions);
		this.activity = Objects.requireNonNull(activity);
	}

	@Override
	public boolean isValid(UUID userID, long sessionVersion, String sessionIDHash) {

		Optional<String> sessionID = findVerifiedSessionID(userID, sessionVersion, sessionIDHash);

		if (sessionID.isEmpty()) {
			return false;
		}

		SessionActivityStore.State state = activity.find(sessionID.get());

		return state != null && state.mfaVerified();
	}

	private Optional<String> findVerifiedSessionID(UUID userID, long sessionVersion, String sessionIDHash) {

		if (userID == null || sessionVersion < 0 || !SessionIDHasher.isValidHash(sessionIDHash)) {
			return Optional.empty();
		}

		for (Session session : sessions.findByPrincipalName(userID.toString()).values()) {

			String currentSessionIDHash = SessionIDHasher.hash(session.getId());

			if (sessionIDHash.equals(currentSessionIDHash)) {

				if (session.isExpired()) {
					return Optional.empty();
				}

				Object value = session.getAttribute(
						HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY);

				if (!(value instanceof SecurityContext context)) {
					return Optional.empty();
				}

				Authentication authentication = context.getAuthentication();

				if (authentication == null || !authentication.isAuthenticated()) {
					return Optional.empty();
				}

				if (!(authentication.getPrincipal() instanceof UserAccount account)) {
					return Optional.empty();
				}

				if (!account.getUserID().equals(userID) || sessionVersion != account.getSessionVersion()) {
					return Optional.empty();
				}

				return Optional.of(session.getId());

			}

		}

		return Optional.empty();
	}

	@Override
	public Optional<Instant> validateTogether(UUID firstUserID, long firstSessionVersion, String firstSessionIDHash,
			UUID secondUserID, long secondSessionVersion, String secondSessionIDHash) {

		if (firstUserID == null || secondUserID == null || firstUserID.equals(secondUserID)) {
			return Optional.empty();
		}

		Optional<String> firstUserSessionID = findVerifiedSessionID(firstUserID, firstSessionVersion,
				firstSessionIDHash);
		Optional<String> secondUserSessionID = findVerifiedSessionID(secondUserID, secondSessionVersion,
				secondSessionIDHash);

		if (firstUserSessionID.isEmpty() || secondUserSessionID.isEmpty()
				|| firstUserSessionID.get().equals(secondUserSessionID.get())) {
			return Optional.empty();
		}

		return activity.validateTogether(firstUserSessionID.get(), secondUserSessionID.get());
	}
}
