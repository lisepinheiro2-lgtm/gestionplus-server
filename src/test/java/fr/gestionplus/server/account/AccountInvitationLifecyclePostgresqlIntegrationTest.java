package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.support.TransactionTemplate;

import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.persistence.UserCredentialEntity;

class AccountInvitationLifecyclePostgresqlIntegrationTest extends AbstractAccountInvitationPostgresqlIntegrationTest {

	@Test
	void reintegrationKeepsDisabledUntilDeliveryAndRequiresTheHolderToActivate() {
		UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(UserRole.EMPLOYEE), structureID);
		UUID userID = former.getUserID();
		joinTenant(userID);
		credentials.saveAndFlush(new UserCredentialEntity(new UserCredential(userID, "old-hash")));
		membershipStore.save(new StructureMembership(userID, structureID, Set.of(UserRole.EMPLOYEE)));

		Map<UUID, Set<UserRole>> roles = Map.of(structureID, Set.of(UserRole.TEAM_LEAD));
		doReturn(false).when(sender).send(anyString(), any(), anyString());

		assertFalse(invitations.inviteForReintegration(userID, structureID, roles, null).sent());
		assertEquals(UserAccountStatus.DISABLED, account(userID).getStatu());
		assertTrue(membershipStore.findByUser(userID).isEmpty());
		assertTrue(invitationStore.findByAccount(userID).isEmpty());

		membershipStore.save(new StructureMembership(userID, structureID, Set.of(UserRole.EMPLOYEE)));

		doAnswer(call -> {
			assertEquals(UserAccountStatus.DISABLED, account(userID).getStatu());
			assertTrue(membershipStore.findByUser(userID).isEmpty());
			sent.add(new SentInvitation(call.getArgument(0), call.getArgument(1), call.getArgument(2), Instant.now()));
			return true;
		}).when(sender).send(anyString(), any(), anyString());

		assertTrue(awaitDelivered(
				invitations.inviteForReintegration(userID, structureID, roles, "updated@example.invalid")).sent());
		UserAccount pending = account(userID);
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, pending.getStatu());
		assertEquals(former.getEmployeeID(), pending.getEmployeeID());
		assertEquals(former.getLastName(), pending.getLastName());
		assertTrue(pending.getRoles().isEmpty());
		assertTrue(pending.getSessionVersion() > former.getSessionVersion());
		assertEquals(Set.of(UserRole.TEAM_LEAD), membershipStore.findByUserAndStructure(userID, structureID)
				.orElseThrow().getRoles());
		assertFalse(authorizationService.canInStructure(userID, structureID, Permission.VIEW_TEAM_SCHEDULE));

		SentInvitation message = sent.getLast();
		assertEquals("updated@example.invalid", message.email());
		Invitation invitation = invitationStore.findByAccount(userID).orElseThrow();
		assertNotEquals(message.token(), invitation.tokenDigest());
		assertFalse(invitation.expiresAt().isBefore(message.deliveredAt().plus(Duration.ofDays(7)).minusMillis(1)));

		SecurityContextHolder.clearContext();
		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "wrong-proof"));
		assertEquals(UserAccountStatus.PENDING_ACTIVATION, account(userID).getStatu());

		allowActivation(userID, message);
		activate(userID, message, "verified-proof");
		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertEquals("new-hash", credentials.findById(userID).orElseThrow().toDomain().getPasswordHash());
		assertTrue(authorizationService.canInStructure(userID, structureID, Permission.VIEW_TEAM_SCHEDULE));
		assertNull(invitationStore.findByAccount(userID).orElseThrow().tokenDigest());
		assertThrows(AccessDeniedException.class, () -> activate(userID, message, "verified-proof"));
	}

	@Test
	void compromisedActivationRevokesTheSessionsAndRecordsTheIncident() {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		activate(userID, message, "verified-proof");
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, account(userID));

		invitations.disableCompromisedActivation(userID, structureID);

		assertDisabledWithoutAccess(userID);
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(1L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events event
				JOIN audit_targets target ON event.event_id = target.event_id
				WHERE event.action = 'INVITATION_FRAUD_DISABLE' AND target.target_id = ?
				""", Long.class, message.invitationID()));
	}

	@Test
	void incidentDeactivationCannotRemoveAProtectedAdmin() {
		UUID userID = preparePendingAccount();
		SentInvitation message = sendInitial(userID);
		allowActivation(userID, message);
		activate(userID, message, "verified-proof");
		jdbcTemplate.update("""
				INSERT INTO structure_membership_roles (user_id, structure_id, role)
				VALUES (?, ?, 'ADMIN')
				""", userID, structureID);

		assertThrows(AccessDeniedException.class, () -> invitations.disableCompromisedActivation(userID, structureID));

		assertEquals(UserAccountStatus.ACTIVE, account(userID).getStatu());
		assertTrue(membershipStore.findByUserAndStructure(userID, structureID).orElseThrow()
				.getRoles().contains(UserRole.ADMIN));
	}

	@Test
	void managerCanOnlyPrepareReintegrationForAnExplicitlyAssignedEmployee() {
		UserAccount manager = createAccount(UserAccountStatus.ACTIVE, Set.of(), structureID);
		UserAccount former = createAccount(UserAccountStatus.DISABLED, Set.of(), structureID);
		UUID userID = former.getUserID();
		joinTenant(manager.getUserID());
		joinTenant(userID);
		accessService.addStructureAccess(manager.getUserID(), structureID, Set.of(UserRole.MANAGER));

		UserAccount managerSession = account(manager.getUserID());
		authenticate(managerSession);
		Map<UUID, Set<UserRole>> roles = Map.of(structureID, Set.of(UserRole.EMPLOYEE));
		assertThrows(AccessDeniedException.class,
				() -> invitations.inviteForReintegration(userID, structureID, roles, null));

		authenticate(actor);
		responsibilityService.changeResponsibleManager(structureID, former.getEmployeeID(), manager.getUserID());
		authenticate(managerSession);
		assertThrows(AccessDeniedException.class, () -> invitations.inviteForReintegration(userID, structureID,
				Map.of(structureID, Set.of(UserRole.SECRETARY)), null));
		assertTrue(awaitDelivered(invitations.inviteForReintegration(userID, structureID, roles, null)).sent());
	}

	@Test
	void reintegrationConcurrentWithTheLastRevocationKeepsTheAccountInactive() throws Exception {
		UserAccount former = createAccount(UserAccountStatus.ACTIVE, Set.of(UserRole.EMPLOYEE), structureID);
		UUID userID = former.getUserID();
		joinTenant(userID);
		accessService.addStructureAccess(userID, structureID, Set.of(UserRole.EMPLOYEE));
		UUID preparedStructure = createStructure();
		String sessionID = UUID.randomUUID().toString();
		sessionRegistry.registerNewSession(sessionID, former);
		CyclicBarrier start = new CyclicBarrier(2);
		boolean reintegrated;

		try (ExecutorService workers = Executors.newFixedThreadPool(2)) {
			Future<Boolean> preparation = workers.submit(() -> {
				authenticate(actor);

				try {
					start.await(5, TimeUnit.SECONDS);
					AccountInvitationService.Delivery delivery = new TransactionTemplate(transactionManager)
							.execute(transaction -> {
								jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
								return invitations.inviteForReintegration(userID, preparedStructure,
										Map.of(preparedStructure, Set.of(UserRole.EMPLOYEE)), null);
							});
					return awaitDelivered(delivery).sent();
				} catch (AccessDeniedException denied) {
					return false;
				} finally {
					SecurityContextHolder.clearContext();
				}

			});
			Future<?> revocation = workers.submit(() -> {
				authenticate(actor);

				try {
					start.await(5, TimeUnit.SECONDS);
					new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
						jdbcTemplate.execute("SET LOCAL lock_timeout = '5s'");
						accessService.revokeStructureAccess(userID, structureID);
					});
					return null;
				} finally {
					SecurityContextHolder.clearContext();
				}

			});
			reintegrated = preparation.get(15, TimeUnit.SECONDS);
			revocation.get(15, TimeUnit.SECONDS);
		}

		assertEquals(reintegrated ? UserAccountStatus.PENDING_ACTIVATION : UserAccountStatus.DISABLED,
				account(userID).getStatu());
		assertTrue(account(userID).getRoles().isEmpty());
		assertTrue(membershipStore.findByUserAndStructure(userID, structureID).isEmpty());
		assertEquals(reintegrated, membershipStore.findByUserAndStructure(userID, preparedStructure).isPresent());
		assertEquals(reintegrated, invitationStore.findByAccount(userID).isPresent());
		assertFalse(authorizationService.canInStructure(userID, preparedStructure, Permission.VIEW_OWN_SCHEDULE));
		assertTrue(authorizationService.hasTenantMembership(userID, tenantID));
		assertTrue(sessionRegistry.getSessionInformation(sessionID).isExpired());
		assertEquals(reintegrated ? 1L : 0L, jdbcTemplate.queryForObject("""
				SELECT COUNT(*) FROM audit_events event JOIN audit_targets target ON event.event_id = target.event_id
				WHERE event.action = 'INVITATION_CREATE' AND event.result = 'SUCCESS' AND target.target_id = ?
				""", Long.class, userID));
	}
}
