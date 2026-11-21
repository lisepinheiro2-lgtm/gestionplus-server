package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.transaction.PlatformTransactionManager;

import fr.gestionplus.server.account.AccountInvitationDeliveryStore.Request;
import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.auth.CredentialService;

class AccountActivationServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
	private static final UUID INVITATION_ID = UUID.randomUUID();
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID STRUCTURE_ID = UUID.randomUUID();
	private static final String TOKEN = "Aa09_-".repeat(7) + "Z";

	private final AccountStore accounts = mock(AccountStore.class);
	private final AccountInvitationStore invitations = mock(AccountInvitationStore.class);
	private final ActivationIdentityChallengeStore challenges = mock(ActivationIdentityChallengeStore.class);
	private final AccountInvitationDeliveryStore deliveries = mock(AccountInvitationDeliveryStore.class);
	private final AccountInvitationScope scope = mock(AccountInvitationScope.class);
	private final AuditService audit = mock(AuditService.class);
	private final CredentialService credentials = mock(CredentialService.class);
	private final ActivationIdentityChallengeService challengeService = mock(ActivationIdentityChallengeService.class);
	private final ObjectProvider<ActivationIdentityVerifier> verifiers = mock();
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private AccountActivationService service;

	@BeforeEach
	void setUp() {
		ObjectProvider<Clock> clocks = mock();
		when(clocks.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
		service = new AccountActivationService(accounts, invitations, challenges, deliveries, scope, audit,
				credentials, challengeService, verifiers, transactions, clocks);
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.of(
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, InvitationTokenIssuer.digest(TOKEN),
						NOW.plusSeconds(3600), null)));
	}

	@ParameterizedTest
	@MethodSource("validTokens")
	void requestsACodeOnlyAfterValidatingTheInvitation(String token) {
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.of(
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, InvitationTokenIssuer.digest(token),
						NOW.plusSeconds(1), null)));

		service.requestActivationChallenge(INVITATION_ID, token);

		var order = inOrder(invitations, challengeService);
		order.verify(invitations).findByID(INVITATION_ID);
		order.verify(challengeService).createChallenge(INVITATION_ID, InvitationTokenIssuer.digest(token));
		verifyNoMoreInteractions(invitations, challengeService);
		verifyNoInteractions(deliveries);
		assertNoActivationWork();
	}

	@Test
	void rejectsANullInvitationBeforeReadingOrCreatingAnything() {
		assertThrows(IllegalArgumentException.class, () -> service.requestActivationChallenge(null, TOKEN));

		verifyNoInteractions(invitations, deliveries, challengeService);
		assertNoActivationWork();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@MethodSource("malformedTokens")
	void rejectsMalformedTokensBeforeReadingOrCreatingAnything(String token) {
		assertThrows(IllegalArgumentException.class, () -> service.requestActivationChallenge(INVITATION_ID, token));

		verifyNoInteractions(invitations, deliveries, challengeService);
		assertNoActivationWork();
	}

	@Test
	void rejectsAnUnknownInvitationWithoutCreatingACode() {
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.empty());

		assertThrows(AccessDeniedException.class, () -> service.requestActivationChallenge(INVITATION_ID, TOKEN));

		verify(deliveries).findByInvitation(INVITATION_ID);
		verifyNoInteractions(challengeService);
		assertNoActivationWork();
	}

	@ParameterizedTest
	@MethodSource("unusableInvitations")
	void rejectsUnusableInvitationsWithoutCreatingACode(Invitation invitation) {
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.of(invitation));

		assertThrows(AccessDeniedException.class, () -> service.requestActivationChallenge(INVITATION_ID, TOKEN));

		verifyNoInteractions(challengeService);
		assertNoActivationWork();
	}

	@Test
	void rejectsAWellFormedTokenBelongingToAnotherInvitation() {
		assertThrows(AccessDeniedException.class,
				() -> service.requestActivationChallenge(INVITATION_ID, "b".repeat(43)));

		verifyNoInteractions(deliveries, challengeService);
		assertNoActivationWork();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void reportsPendingDeliveryOnlyForItsMatchingToken(boolean invitationExists) {
		preparePendingDelivery(invitationExists, TOKEN);

		assertThrows(InvitationDeliveryPendingException.class,
				() -> service.requestActivationChallenge(INVITATION_ID, TOKEN));

		verifyNoInteractions(challengeService);
		assertNoActivationWork();
	}

	@ParameterizedTest
	@ValueSource(booleans = { false, true })
	void rejectsAWrongTokenWithoutDisclosingThePendingDelivery(boolean invitationExists) {
		preparePendingDelivery(invitationExists, "b".repeat(43));

		assertThrows(AccessDeniedException.class, () -> service.requestActivationChallenge(INVITATION_ID, TOKEN));

		verifyNoInteractions(challengeService);
		assertNoActivationWork();
	}

	@ParameterizedTest
	@MethodSource("challengeFailures")
	void propagatesChallengeCreationFailuresWithoutStartingActivation(RuntimeException failure) {
		doThrow(failure).when(challengeService).createChallenge(INVITATION_ID, InvitationTokenIssuer.digest(TOKEN));

		assertSame(failure, assertThrows(failure.getClass(),
				() -> service.requestActivationChallenge(INVITATION_ID, TOKEN)));

		verify(challengeService).createChallenge(INVITATION_ID, InvitationTokenIssuer.digest(TOKEN));
		verifyNoMoreInteractions(challengeService);
		assertNoActivationWork();
	}

	@ParameterizedTest
	@NullAndEmptySource
	@MethodSource("malformedTokens")
	void keepsRejectingMalformedActivationTokensBeforeCheckingThePassword(String token) {
		assertThrows(IllegalArgumentException.class, () -> service.completeActivation(INVITATION_ID, token,
				"verified-proof", "activated-user", "valid-test-password"));

		verifyNoInteractions(invitations, deliveries, challengeService);
		assertNoActivationWork();
	}

	private void assertNoActivationWork() {
		verifyNoInteractions(accounts, challenges, scope, audit, credentials, verifiers, transactions);
	}

	private void preparePendingDelivery(boolean invitationExists, String token) {
		when(invitations.findByID(INVITATION_ID)).thenReturn(invitationExists
				? Optional.of(new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, null, null, null))
				: Optional.empty());
		Request request = new Request(UUID.randomUUID(), INVITATION_ID, null, USER_ID, STRUCTURE_ID,
				UUID.randomUUID(), 0, 0, "activation@example.invalid", AuditAction.INVITATION_CREATE,
				false, InvitationTokenIssuer.digest(token), Map.of());
		when(deliveries.findByInvitation(INVITATION_ID)).thenReturn(Optional.of(request));
	}

	private static Stream<String> validTokens() {
		return Stream.of(TOKEN, "a".repeat(43), "-".repeat(43), "_".repeat(43));
	}

	private static Stream<String> malformedTokens() {
		return Stream.of(" ", "a".repeat(42), "a".repeat(44), " " + TOKEN, TOKEN + " ",
				"a".repeat(42) + "+", "a".repeat(42) + "/", "a".repeat(42) + "=",
				"a".repeat(42) + "é", "a".repeat(42) + "\n", TOKEN + "\n");
	}

	private static Stream<Invitation> unusableInvitations() {
		String digest = InvitationTokenIssuer.digest(TOKEN);
		return Stream.of(
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, null, NOW.plusSeconds(3600), null),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, digest, null, null),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, digest, NOW.minusSeconds(1), null),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, digest, NOW, null),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, null, null, NOW.minusSeconds(1)));
	}

	private static Stream<RuntimeException> challengeFailures() {
		return Stream.of(new AccountActivationConflictException(), new IllegalStateException("SMS indisponible."));
	}
}
