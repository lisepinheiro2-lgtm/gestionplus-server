package fr.gestionplus.server.account;

import static fr.gestionplus.server.account.SmsIdentityChallenge.Purpose.ACCOUNT_ACTIVATION;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;

class ActivationIdentityChallengeServiceTest {

	private static final Instant NOW = Instant.parse("2026-11-17T12:00:00Z");
	private static final UUID INVITATION_ID = UUID.randomUUID();
	private static final UUID USER_ID = UUID.randomUUID();
	private static final UUID STRUCTURE_ID = UUID.randomUUID();
	private static final String PHONE = "+12025550100";
	private static final String CODE = "000042";
	private static final String DIGEST = "a".repeat(64);

	private final SmsIdentityChallengeStore challenges = mock(SmsIdentityChallengeStore.class);
	private final AccountInvitationStore invitations = mock(AccountInvitationStore.class);
	private final AccountStore accounts = mock(AccountStore.class);
	private final ActivationCodeGenerator generator = mock(ActivationCodeGenerator.class);
	private final ActivationCodeDigester digester = mock(ActivationCodeDigester.class);
	private final SmsMessageSender sender = mock(SmsMessageSender.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private ActivationIdentityChallengeService service;
	private final Clock clock = mock(Clock.class);

	@BeforeEach
	void setUp() {
		ObjectProvider<Clock> clocks = mock();
		when(clock.instant()).thenReturn(NOW);
		when(clocks.getIfAvailable(any())).thenReturn(clock);
		SmsIdentityChallengeService smsChallenges = new SmsIdentityChallengeService(challenges, accounts,
				mock(AuthenticatedUserProvider.class), mock(CurrentSessionProvider.class), generator, digester, sender, clocks, transactions);
		service = new ActivationIdentityChallengeService(challenges, invitations, accounts, smsChallenges, clocks);
		Invitation invitation = new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, "invitation-digest",
				NOW.plusSeconds(3600), null);
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.of(invitation));
		when(invitations.findByIDForUpdate(INVITATION_ID)).thenReturn(Optional.of(invitation));
		UserAccount account = UserAccount.pending(USER_ID, UUID.randomUUID(), "Exemple", "Test",
				"sms@example.invalid", PHONE);
		when(accounts.findById(USER_ID)).thenReturn(Optional.of(account));
		when(accounts.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(account));
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(Optional.empty());
		when(challenges.confirmSend(eq(ACCOUNT_ACTIVATION), any(), any())).thenReturn(true);
		when(generator.generate()).thenReturn(CODE);
		when(digester.digest(eq(ACCOUNT_ACTIVATION), any(), eq(CODE))).thenReturn(DIGEST);
		when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
	}

	@Test
	void persistsOnlyTheDigestAndSendsTheCodeAfterCommit() {
		service.createChallenge(INVITATION_ID, "invitation-digest");

		ArgumentCaptor<SmsIdentityChallenge> saved = ArgumentCaptor.forClass(SmsIdentityChallenge.class);
		var order = inOrder(accounts, challenges, transactions, sender);
		order.verify(transactions).getTransaction(any());
		order.verify(accounts).findByIdForUpdate(USER_ID);
		order.verify(challenges).findCurrentByInvitationAndUser(INVITATION_ID, USER_ID);
		order.verify(challenges).countByInvitationAndUser(INVITATION_ID, USER_ID);
		order.verify(challenges).replaceCurrent(saved.capture(), eq(NOW));
		order.verify(transactions).commit(any());
		order.verify(sender).send(ACCOUNT_ACTIVATION, PHONE, CODE);
		order.verify(challenges).confirmSend(ACCOUNT_ACTIVATION, saved.getValue().challengeID(), NOW);

		SmsIdentityChallenge challenge = saved.getValue();
		assertEquals(USER_ID, challenge.binding().userID());
		assertEquals(INVITATION_ID, challenge.binding().invitationID());
		assertEquals(PHONE, challenge.binding().phoneNumber());
		assertEquals(DIGEST, challenge.codeDigest());
		assertNotEquals(CODE, challenge.codeDigest());
		assertEquals(0, challenge.failedAttempts());
		assertEquals(NOW, challenge.createdAt());
		assertEquals(NOW.plusSeconds(300), challenge.expiresAt());
		assertNull(challenge.consumedAt());
		assertNull(challenge.revokedAt());
		verify(digester).digest(ACCOUNT_ACTIVATION, challenge.challengeID(), CODE);
		verify(challenges, never()).revokeCurrent(eq(ACCOUNT_ACTIVATION), any(), any());
	}

	@Test
	void rejectsAnUnknownInvitationBeforeGeneratingOrSendingACode() {
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.empty());

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		verifyNoInteractions(accounts, challenges, generator, digester, sender, transactions);
	}

	@ParameterizedTest
	@MethodSource("unusableInvitations")
	void rejectsUnusableInvitationsBeforeLookingUpTheAccount(Invitation invitation) {
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.of(invitation));

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		verifyNoInteractions(accounts, challenges, generator, digester, sender, transactions);
	}

	@Test
	void rejectsAMissingAccountBeforeGeneratingOrSendingACode() {
		when(accounts.findById(USER_ID)).thenReturn(Optional.empty());

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		verifyNoInteractions(challenges, generator, digester, sender, transactions);
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = "PENDING_ACTIVATION", mode = EnumSource.Mode.EXCLUDE)
	void rejectsAccountsThatAreNotPendingActivation(UserAccountStatus status) {
		UserAccount account = new UserAccount(USER_ID, "existing-test-user", status, Set.of(), UUID.randomUUID(),
				"Exemple", "Test", "sms@example.invalid", PHONE);
		when(accounts.findById(USER_ID)).thenReturn(Optional.of(account));

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		verifyNoInteractions(challenges, generator, digester, sender, transactions);
	}

	@Test
	void rejectsAnAccountThatDisappearsBeforeTheLockedRead() {
		when(accounts.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));

		verify(accounts).findByIdForUpdate(USER_ID);
		verify(challenges, never()).replaceCurrent(any(), any());
		verify(transactions).rollback(any());
		verify(transactions, never()).commit(any());
		verifyNoInteractions(sender);
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = "PENDING_ACTIVATION", mode = EnumSource.Mode.EXCLUDE)
	void rejectsAnAccountThatIsNoLongerPendingAtTheLockedRead(UserAccountStatus status) {
		UserAccount account = new UserAccount(USER_ID, "existing-test-user", status, Set.of(), UUID.randomUUID(),
				"Exemple", "Test", "sms@example.invalid", PHONE);
		when(accounts.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(account));

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));

		verify(accounts).findByIdForUpdate(USER_ID);
		verify(challenges, never()).replaceCurrent(any(), any());
		verify(transactions).rollback(any());
		verify(transactions, never()).commit(any());
		verifyNoInteractions(sender);
	}

	@ParameterizedTest
	@ValueSource(longs = { 0, 1, 59 })
	void rejectsAResendBeforeSixtySeconds(long ageInSeconds) {
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID))
				.thenReturn(Optional.of(currentChallenge(NOW.minusSeconds(ageInSeconds), NOW.plusSeconds(300))));

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		verify(challenges, never()).countByInvitationAndUser(any(), any());
		verifyNoInteractions(generator, digester, sender, transactions);
	}

	@Test
	void rejectsAResendThatBecomesBlockedAfterTheInitialCheck() {
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(currentChallenge(NOW, NOW.plusSeconds(300))));

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));

		var order = inOrder(accounts, challenges, transactions);
		order.verify(transactions).getTransaction(any());
		order.verify(accounts).findByIdForUpdate(USER_ID);
		order.verify(challenges).findCurrentByInvitationAndUser(INVITATION_ID, USER_ID);
		order.verify(transactions).rollback(any());
		verify(challenges, never()).replaceCurrent(any(), any());
		verify(transactions, never()).commit(any());
		verifyNoInteractions(sender);
	}

	@ParameterizedTest
	@ValueSource(longs = { 60, 61 })
	void allowsAResendOnceTheCooldownHasElapsed(long ageInSeconds) {
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID))
				.thenReturn(Optional.of(currentChallenge(NOW.minusSeconds(ageInSeconds), NOW.plusSeconds(300))));

		service.createChallenge(INVITATION_ID, "invitation-digest");

		verify(challenges).replaceCurrent(any(), eq(NOW));
		verify(sender).send(ACCOUNT_ACTIVATION, PHONE, CODE);
	}

	@Test
	void replacesAnExpiredChallengeWithoutApplyingItsCooldown() {
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID))
				.thenReturn(Optional.of(currentChallenge(NOW.minusSeconds(1), NOW)));

		service.createChallenge(INVITATION_ID, "invitation-digest");

		verify(challenges).replaceCurrent(any(), eq(NOW));
		verify(sender).send(ACCOUNT_ACTIVATION, PHONE, CODE);
	}

	@ParameterizedTest
	@ValueSource(ints = { 3, 4 })
	void rejectsCreationOnceTheAttemptLimitIsReached(int count) {
		when(challenges.countByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(count);

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		verify(challenges, never()).replaceCurrent(any(), any());
		verifyNoInteractions(generator, digester, sender, transactions);
	}

	@ParameterizedTest
	@ValueSource(ints = { 3, 4 })
	void rejectsCreationWhenTheAttemptLimitIsReachedAfterTheInitialCheck(int count) {
		when(challenges.countByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(2, count);

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));

		var order = inOrder(accounts, challenges, transactions);
		order.verify(transactions).getTransaction(any());
		order.verify(accounts).findByIdForUpdate(USER_ID);
		order.verify(challenges).countByInvitationAndUser(INVITATION_ID, USER_ID);
		order.verify(transactions).rollback(any());
		verify(challenges, never()).replaceCurrent(any(), any());
		verify(transactions, never()).commit(any());
		verifyNoInteractions(sender);
	}

	@Test
	void allowsTheThirdAttempt() {
		when(challenges.countByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(2);

		service.createChallenge(INVITATION_ID, "invitation-digest");

		verify(challenges).replaceCurrent(any(), eq(NOW));
		verify(sender).send(ACCOUNT_ACTIVATION, PHONE, CODE);
	}

	@Test
	void doesNotPersistOrSendWhenDigestingFails() {
		IllegalStateException failure = new IllegalStateException("Clé de défi indisponible.");
		when(digester.digest(eq(ACCOUNT_ACTIVATION), any(), eq(CODE))).thenThrow(failure);

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest")));
		verify(challenges, never()).replaceCurrent(any(), any());
		verifyNoInteractions(sender, transactions);
	}

	@Test
	void rollsBackAPersistenceFailureWithoutSending() {
		IllegalStateException failure = new IllegalStateException("Stockage indisponible.");
		doThrow(failure).when(challenges).replaceCurrent(any(), any());

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest")));
		verify(transactions).rollback(any());
		verify(transactions, never()).commit(any());
		verifyNoInteractions(sender);
	}

	@Test
	void neverSendsWhenTheCommitFails() {
		IllegalStateException failure = new IllegalStateException("Validation de transaction impossible.");
		doThrow(failure).when(transactions).commit(any());

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest")));
		verifyNoInteractions(sender);
	}

	@Test
	void revokesThePersistedChallengeWhenSendingFailsAndRethrowsTheFailure() {
		IllegalStateException failure = new IllegalStateException("Envoi SMS indisponible.");
		doThrow(failure).when(sender).send(ACCOUNT_ACTIVATION, PHONE, CODE);

		assertSame(failure, assertThrows(IllegalStateException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest")));

		ArgumentCaptor<SmsIdentityChallenge> saved = ArgumentCaptor.forClass(SmsIdentityChallenge.class);
		var order = inOrder(challenges, transactions, sender);
		order.verify(challenges).replaceCurrent(saved.capture(), eq(NOW));
		order.verify(transactions).commit(any());
		order.verify(sender).send(ACCOUNT_ACTIVATION, PHONE, CODE);
		order.verify(challenges).revokeCurrent(ACCOUNT_ACTIVATION, saved.getValue().challengeID(), NOW);
	}

	@Test
	void usesThePhoneNumberReadUnderTheAccountLock() {
		String updatedPhone = "+12025550199";
		UserAccount updated = UserAccount.pending(USER_ID, UUID.randomUUID(), "Exemple", "Test",
				"sms@example.invalid", updatedPhone);
		when(accounts.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(updated));

		service.createChallenge(INVITATION_ID, "invitation-digest");

		ArgumentCaptor<SmsIdentityChallenge> saved = ArgumentCaptor.forClass(SmsIdentityChallenge.class);
		verify(challenges).replaceCurrent(saved.capture(), eq(NOW));
		assertEquals(updatedPhone, saved.getValue().binding().phoneNumber());
		verify(sender).send(ACCOUNT_ACTIVATION, updatedPhone, CODE);
		verify(sender, never()).send(ACCOUNT_ACTIVATION, PHONE, CODE);
	}

	@Test
	void rejectsAnInvitationWhoseTokenChangedBeforeTheLockedRead() {
		when(invitations.findByIDForUpdate(INVITATION_ID)).thenReturn(Optional.of(new Invitation(INVITATION_ID,
				USER_ID, STRUCTURE_ID, "replacement-digest", NOW.plusSeconds(3600), null)));

		assertThrows(ActivationDeniedException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));

		verify(challenges, never()).replaceCurrent(any(), any());
		verifyNoInteractions(sender);
		verify(transactions).rollback(any());
	}

	@Test
	void rechecksTimeAfterWaitingForTheAccountLock() {
		when(accounts.findByIdForUpdate(USER_ID)).thenAnswer(invocation -> {
			when(clock.instant()).thenReturn(NOW.plusSeconds(3600));
			return accounts.findById(USER_ID);
		});

		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));

		verify(challenges, never()).replaceCurrent(any(), any());
		verifyNoInteractions(sender);
	}

	@Test
	void preservesBothFailuresWhenSendingAndImmediateRevocationFail() {
		IllegalStateException sendingFailure = new IllegalStateException("Envoi indisponible.");
		IllegalStateException revocationFailure = new IllegalStateException("Révocation indisponible.");
		doThrow(sendingFailure).when(sender).send(ACCOUNT_ACTIVATION, PHONE, CODE);
		doThrow(revocationFailure).when(challenges).revokeCurrent(eq(ACCOUNT_ACTIVATION), any(), any());

		assertSame(sendingFailure, assertThrows(IllegalStateException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest")));

		assertArrayEquals(new Throwable[] { revocationFailure }, sendingFailure.getSuppressed());
	}

	@Test
	void revokesAChallengeWhoseSuccessfulSendCannotBeConfirmed() {
		when(challenges.confirmSend(eq(ACCOUNT_ACTIVATION), any(), any())).thenReturn(false);

		assertThrows(ActivationMessageDeliveryException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));

		verify(challenges).revokeCurrent(eq(ACCOUNT_ACTIVATION), any(), eq(NOW));
	}

	@Test
	void anUnconfirmedSendStillAppliesTheResendCooldown() {
		SmsIdentityChallenge pending = SmsIdentityChallenge.forActivation(UUID.randomUUID(), USER_ID, PHONE,
				INVITATION_ID, DIGEST, 0, NOW, NOW.plusSeconds(300), null, null, null, NOW.plusSeconds(120));
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(Optional.of(pending));
		assertThrows(AccountActivationConflictException.class,
				() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		verifyNoInteractions(sender, transactions);
	}

	@Test
	void refusesAnEnclosingTransactionBeforeReadingOrSendingAnything() {
		org.springframework.transaction.support.TransactionSynchronizationManager.setActualTransactionActive(true);

		try {
			assertThrows(org.springframework.transaction.IllegalTransactionStateException.class,
					() -> service.createChallenge(INVITATION_ID, "invitation-digest"));
		} finally {
			org.springframework.transaction.support.TransactionSynchronizationManager.clear();
		}

		verifyNoInteractions(invitations, accounts, challenges, sender);
	}

	private SmsIdentityChallenge currentChallenge(Instant createdAt, Instant expiresAt) {
		return SmsIdentityChallenge.forActivation(UUID.randomUUID(), USER_ID, PHONE, INVITATION_ID, DIGEST, 0,
				createdAt, expiresAt, null, null, createdAt, createdAt.plusSeconds(120));
	}

	private static Stream<Invitation> unusableInvitations() {
		return Stream.of(
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, null, NOW.plusSeconds(3600), null),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, "digest", NOW.plusSeconds(3600), NOW),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, "digest", null, null),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, "digest", NOW, null),
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, "digest", NOW.minusSeconds(1), null));
	}
}
