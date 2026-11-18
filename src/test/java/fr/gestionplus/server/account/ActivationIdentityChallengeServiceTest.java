package fr.gestionplus.server.account;

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
import java.time.ZoneOffset;
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
import fr.gestionplus.server.account.ActivationIdentityChallengeStore.ActivationIdentityChallenge;
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

	private final ActivationIdentityChallengeStore challenges = mock(ActivationIdentityChallengeStore.class);
	private final AccountInvitationStore invitations = mock(AccountInvitationStore.class);
	private final AccountStore accounts = mock(AccountStore.class);
	private final ActivationCodeGenerator generator = mock(ActivationCodeGenerator.class);
	private final ActivationCodeDigester digester = mock(ActivationCodeDigester.class);
	private final ActivationMessageSender sender = mock(ActivationMessageSender.class);
	private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
	private ActivationIdentityChallengeService service;

	@BeforeEach
	void setUp() {
		ObjectProvider<Clock> clocks = mock();
		when(clocks.getIfAvailable(any())).thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
		service = new ActivationIdentityChallengeService(challenges, invitations, accounts, generator, digester,
				sender, clocks, transactions);
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.of(
				new Invitation(INVITATION_ID, USER_ID, STRUCTURE_ID, "invitation-digest", NOW.plusSeconds(3600),
						null)));
		UserAccount account = UserAccount.pending(USER_ID, UUID.randomUUID(), "Exemple", "Test",
				"sms@example.invalid", PHONE);
		when(accounts.findById(USER_ID)).thenReturn(Optional.of(account));
		when(accounts.findByIdForUpdate(USER_ID)).thenReturn(Optional.of(account));
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(Optional.empty());
		when(generator.generate()).thenReturn(CODE);
		when(digester.digest(any(), eq(CODE))).thenReturn(DIGEST);
		when(transactions.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
	}

	@Test
	void persistsOnlyTheDigestAndSendsTheCodeAfterCommit() {
		service.createChallenge(INVITATION_ID);

		ArgumentCaptor<ActivationIdentityChallenge> saved = ArgumentCaptor.forClass(ActivationIdentityChallenge.class);
		var order = inOrder(accounts, challenges, transactions, sender);
		order.verify(transactions).getTransaction(any());
		order.verify(accounts).findByIdForUpdate(USER_ID);
		order.verify(challenges).findCurrentByInvitationAndUser(INVITATION_ID, USER_ID);
		order.verify(challenges).countByInvitationAndUser(INVITATION_ID, USER_ID);
		order.verify(challenges).replaceCurrent(saved.capture(), eq(NOW));
		order.verify(transactions).commit(any());
		order.verify(sender).send(PHONE, CODE);

		ActivationIdentityChallenge challenge = saved.getValue();
		assertEquals(USER_ID, challenge.userID());
		assertEquals(INVITATION_ID, challenge.invitationID());
		assertEquals(PHONE, challenge.phoneNumber());
		assertEquals(DIGEST, challenge.codeDigest());
		assertNotEquals(CODE, challenge.codeDigest());
		assertEquals(0, challenge.failedAttempts());
		assertEquals(NOW, challenge.createdAt());
		assertEquals(NOW.plusSeconds(300), challenge.expiresAt());
		assertNull(challenge.consumedAt());
		assertNull(challenge.revokedAt());
		verify(digester).digest(challenge.challengeID(), CODE);
		verify(challenges, never()).revokeCurrent(any(), any());
	}

	@Test
	void rejectsAnUnknownInvitationBeforeGeneratingOrSendingACode() {
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.empty());

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));
		verifyNoInteractions(accounts, challenges, generator, digester, sender, transactions);
	}

	@ParameterizedTest
	@MethodSource("unusableInvitations")
	void rejectsUnusableInvitationsBeforeLookingUpTheAccount(Invitation invitation) {
		when(invitations.findByID(INVITATION_ID)).thenReturn(Optional.of(invitation));

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));
		verifyNoInteractions(accounts, challenges, generator, digester, sender, transactions);
	}

	@Test
	void rejectsAMissingAccountBeforeGeneratingOrSendingACode() {
		when(accounts.findById(USER_ID)).thenReturn(Optional.empty());

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));
		verifyNoInteractions(challenges, generator, digester, sender, transactions);
	}

	@ParameterizedTest
	@EnumSource(value = UserAccountStatus.class, names = "PENDING_ACTIVATION", mode = EnumSource.Mode.EXCLUDE)
	void rejectsAccountsThatAreNotPendingActivation(UserAccountStatus status) {
		UserAccount account = new UserAccount(USER_ID, "existing-test-user", status, Set.of(), UUID.randomUUID(),
				"Exemple", "Test", "sms@example.invalid", PHONE);
		when(accounts.findById(USER_ID)).thenReturn(Optional.of(account));

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));
		verifyNoInteractions(challenges, generator, digester, sender, transactions);
	}

	@Test
	void rejectsAnAccountThatDisappearsBeforeTheLockedRead() {
		when(accounts.findByIdForUpdate(USER_ID)).thenReturn(Optional.empty());

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));

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

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));

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

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));
		verify(challenges, never()).countByInvitationAndUser(any(), any());
		verifyNoInteractions(generator, digester, sender, transactions);
	}

	@Test
	void rejectsAResendThatBecomesBlockedAfterTheInitialCheck() {
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID))
				.thenReturn(Optional.empty())
				.thenReturn(Optional.of(currentChallenge(NOW, NOW.plusSeconds(300))));

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));

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

		service.createChallenge(INVITATION_ID);

		verify(challenges).replaceCurrent(any(), eq(NOW));
		verify(sender).send(PHONE, CODE);
	}

	@Test
	void replacesAnExpiredChallengeWithoutApplyingItsCooldown() {
		when(challenges.findCurrentByInvitationAndUser(INVITATION_ID, USER_ID))
				.thenReturn(Optional.of(currentChallenge(NOW.minusSeconds(1), NOW)));

		service.createChallenge(INVITATION_ID);

		verify(challenges).replaceCurrent(any(), eq(NOW));
		verify(sender).send(PHONE, CODE);
	}

	@ParameterizedTest
	@ValueSource(ints = { 3, 4 })
	void rejectsCreationOnceTheAttemptLimitIsReached(int count) {
		when(challenges.countByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(count);

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));
		verify(challenges, never()).replaceCurrent(any(), any());
		verifyNoInteractions(generator, digester, sender, transactions);
	}

	@ParameterizedTest
	@ValueSource(ints = { 3, 4 })
	void rejectsCreationWhenTheAttemptLimitIsReachedAfterTheInitialCheck(int count) {
		when(challenges.countByInvitationAndUser(INVITATION_ID, USER_ID)).thenReturn(2, count);

		assertThrows(AccountActivationConflictException.class, () -> service.createChallenge(INVITATION_ID));

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

		service.createChallenge(INVITATION_ID);

		verify(challenges).replaceCurrent(any(), eq(NOW));
		verify(sender).send(PHONE, CODE);
	}

	@Test
	void doesNotPersistOrSendWhenDigestingFails() {
		IllegalStateException failure = new IllegalStateException("Clé de défi indisponible.");
		when(digester.digest(any(), eq(CODE))).thenThrow(failure);

		assertSame(failure, assertThrows(IllegalStateException.class, () -> service.createChallenge(INVITATION_ID)));
		verify(challenges, never()).replaceCurrent(any(), any());
		verifyNoInteractions(sender, transactions);
	}

	@Test
	void rollsBackAPersistenceFailureWithoutSending() {
		IllegalStateException failure = new IllegalStateException("Stockage indisponible.");
		doThrow(failure).when(challenges).replaceCurrent(any(), any());

		assertSame(failure, assertThrows(IllegalStateException.class, () -> service.createChallenge(INVITATION_ID)));
		verify(transactions).rollback(any());
		verify(transactions, never()).commit(any());
		verifyNoInteractions(sender);
	}

	@Test
	void neverSendsWhenTheCommitFails() {
		IllegalStateException failure = new IllegalStateException("Validation de transaction impossible.");
		doThrow(failure).when(transactions).commit(any());

		assertSame(failure, assertThrows(IllegalStateException.class, () -> service.createChallenge(INVITATION_ID)));
		verifyNoInteractions(sender);
	}

	@Test
	void revokesThePersistedChallengeWhenSendingFailsAndRethrowsTheFailure() {
		IllegalStateException failure = new IllegalStateException("Envoi SMS indisponible.");
		doThrow(failure).when(sender).send(PHONE, CODE);

		assertSame(failure, assertThrows(IllegalStateException.class, () -> service.createChallenge(INVITATION_ID)));

		ArgumentCaptor<ActivationIdentityChallenge> saved = ArgumentCaptor.forClass(ActivationIdentityChallenge.class);
		var order = inOrder(challenges, transactions, sender);
		order.verify(challenges).replaceCurrent(saved.capture(), eq(NOW));
		order.verify(transactions).commit(any());
		order.verify(sender).send(PHONE, CODE);
		order.verify(challenges).revokeCurrent(saved.getValue().challengeID(), NOW);
	}

	private ActivationIdentityChallenge currentChallenge(Instant createdAt, Instant expiresAt) {
		return new ActivationIdentityChallenge(UUID.randomUUID(), USER_ID, PHONE, INVITATION_ID, DIGEST, 0,
				createdAt, expiresAt, null, null);
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
