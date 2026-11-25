package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.RequestRateLimiter;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.support.SuccessionFixtures;

class SuccessionSecurityTest {

	private static final Instant ISSUED = Instant.parse("2026-11-25T10:00:00Z");
	private static final String OUTGOING_SESSION = "a".repeat(64);
	private static final String SUCCESSOR_SESSION = "b".repeat(64);
	private final UUID outgoing = UUID.randomUUID();
	private final UUID successor = UUID.randomUUID();
	private final SuccessionOffer offer = new SuccessionOffer(UUID.randomUUID(), outgoing, successor, UUID.randomUUID(),
			List.of(), 1L, SuccessionOffer.OfferStatus.ACCEPTED, ISSUED, ISSUED.plusSeconds(86400), null, null);
	private final SuccessionRevision revision = SuccessionFixtures.revision(offer, 1, List.of(), ISSUED);
	private final UserAccount outgoingAccount = account(outgoing, 1);
	private final UserAccount successorAccount = account(successor, 2);
	private final AuthenticatedUserProvider authenticated = mock(AuthenticatedUserProvider.class);
	private final CurrentSessionProvider currentSession = mock(CurrentSessionProvider.class);
	private final SessionValidityVerifier sessionValidity = mock(SessionValidityVerifier.class);
	private final SuccessionConfirmationStore confirmations = mock(SuccessionConfirmationStore.class);
	private final SuccessionSecurity security = new SuccessionSecurity(authenticated, currentSession,
			mock(RequestRateLimiter.class), mock(PrivilegedConfirmationService.class), confirmations, sessionValidity,
			Clock.fixed(ISSUED, ZoneOffset.UTC));

	@BeforeEach
	void prepare() {
		when(authenticated.getCurrentAccount()).thenReturn(successorAccount);
		when(currentSession.getActiveSessionIDHash()).thenReturn(SUCCESSOR_SESSION);
		when(confirmations.findByOfferIDAndUserID(offer.offerID(), 1, outgoing))
				.thenReturn(Optional.of(confirmation(outgoing, 1, OUTGOING_SESSION)));
		when(confirmations.findByOfferIDAndUserID(offer.offerID(), 1, successor))
				.thenReturn(Optional.of(confirmation(successor, 2, SUCCESSOR_SESSION)));
	}

	@Test
	void onlyTheSuccessorCanAuthorizeFromTheExactConfirmedSession() {
		when(authenticated.getCurrentAccount()).thenReturn(outgoingAccount);
		assertThrows(AccessDeniedException.class, this::authorize);
		when(authenticated.getCurrentAccount()).thenReturn(successorAccount);
		when(currentSession.getActiveSessionIDHash()).thenReturn("c".repeat(64));
		assertThrows(AccessDeniedException.class, this::authorize);
		verifyNoInteractions(sessionValidity);
	}

	@Test
	void validatesAgainstTheInstantReturnedByTheJointCheckIncludingTheExpiryBoundary() {
		when(sessionValidity.validateTogether(outgoing, 1, OUTGOING_SESSION, successor, 2, SUCCESSOR_SESSION))
				.thenReturn(Optional.of(ISSUED.plusSeconds(299)), Optional.of(ISSUED.plusSeconds(300)));
		assertEquals(ISSUED.plusSeconds(299), authorize());
		assertThrows(AccessDeniedException.class, this::authorize);
	}

	@Test
	void staleSecurityVersionLeavesTheOfferAndOtherConfirmationIntact() {
		when(confirmations.findByOfferIDAndUserID(offer.offerID(), 1, outgoing))
				.thenReturn(Optional.of(confirmation(outgoing, 0, OUTGOING_SESSION)));
		assertThrows(AccessDeniedException.class, this::authorize);
		verifyNoInteractions(sessionValidity);
	}

	private Instant authorize() {
		var scope = new SuccessionScope.Locked(offer.tenantID(), List.of(), outgoingAccount, successorAccount, null, null,
				SuccessionScope.Eligibility.ELIGIBLE);
		return security.authorizeFinalization(offer, revision, scope);
	}

	private SuccessionConfirmation confirmation(UUID userID, long version, String session) {
		return new SuccessionConfirmation(offer.offerID(), userID, 1, version, session, revision.previewDigest(),
				ISSUED, ISSUED.plusSeconds(300));
	}

	private static UserAccount account(UUID userID, long version) {
		var account = mock(UserAccount.class);
		when(account.getUserID()).thenReturn(userID);
		when(account.getSessionVersion()).thenReturn(version);
		when(account.getStatu()).thenReturn(UserAccountStatus.ACTIVE);
		return account;
	}
}
