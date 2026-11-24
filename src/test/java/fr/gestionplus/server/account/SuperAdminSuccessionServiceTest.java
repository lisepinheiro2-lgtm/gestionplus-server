package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.account.SuperAdminSuccessionService.SuccessionPreview;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.CredentialService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredentialLookup;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

class SuperAdminSuccessionServiceTest {

	private final AuthenticatedUserProvider authenticated = mock(AuthenticatedUserProvider.class);
	private final AuthorizationService authorization = mock(AuthorizationService.class);
	private final TenantOrganizationStore tenants = mock(TenantOrganizationStore.class);
	private final OrganizationalStructureStore structures = mock(OrganizationalStructureStore.class);
	private final StructureMembershipStore memberships = mock(StructureMembershipStore.class);
	private final SuccessionOfferStore offerStore = mock(SuccessionOfferStore.class);
	private final CurrentSessionProvider sessionProvider = mock(CurrentSessionProvider.class);
	private final SessionValidityVerifier sessionValidityVerifier = mock(SessionValidityVerifier.class);
	private final SuccessionConfirmationStore confirmationStore = mock(SuccessionConfirmationStore.class);
	private final String outgoingSession = "1".repeat(64);
	private final String successorSession = "2".repeat(64);
	private final AccountStore accountStore = mock(AccountStore.class);
	private final Clock clock = Clock.fixed(Instant.parse("2026-11-23T12:00:00Z"), ZoneOffset.UTC);
	private final StructureAdminTransferService transfers = mock(StructureAdminTransferService.class);
	private final AccountAccessLifecycle lifecycle = mock(AccountAccessLifecycle.class);
	private final PrivilegedConfirmationVerifier verifier = mock(PrivilegedConfirmationVerifier.class);
	private final AuditService audit = mock(AuditService.class);
	private final UUID tenantID = UUID.randomUUID();
	private final UUID firstStructure = UUID.randomUUID();
	private final UUID secondStructure = UUID.randomUUID();
	private final UUID outgoingID = UUID.randomUUID();
	private final UUID successorID = UUID.randomUUID();
	private final UserAccount outgoing = account(outgoingID, UserAccountStatus.ACTIVE, 2);
	private final UserAccount successor = account(successorID, UserAccountStatus.ACTIVE, 7);
	private SuperAdminSuccessionService service;

	@BeforeEach
	void prepare() {
		StaticListableBeanFactory beans = new StaticListableBeanFactory();
		beans.addBean("verifier", verifier);
		service = new SuperAdminSuccessionService(authenticated, sessionProvider, sessionValidityVerifier,
				authorization, tenants, structures, memberships,
				offerStore, accountStore, confirmationStore, transfers, lifecycle,
				new PrivilegedConfirmationService(beans.getBeanProvider(PrivilegedConfirmationVerifier.class),
						sessionProvider, mock(PrivilegedConfirmationProofStore.class), mock(AccountStore.class),
						authenticated,
						mock(UserCredentialLookup.class), mock(CredentialService.class), mock(AccountMfaService.class),
						Clock.systemUTC()),
				audit, clock);
		when(authenticated.getCurrentAccount()).thenReturn(outgoing);
		when(tenants.findById(tenantID)).thenReturn(Optional.of(
				new TenantOrganization(tenantID, "Entreprise test", outgoingID)));
		when(tenants.replaceSuperAdmin(tenantID, outgoingID, successorID)).thenReturn(true);
		when(structures.findIDsByTenant(tenantID)).thenReturn(List.of(firstStructure, secondStructure));
		when(structures.findByIdForUpdate(firstStructure)).thenReturn(Optional.of(
				new OrganizationalStructure(firstStructure, "Première structure", tenantID)));
		when(structures.findByIdForUpdate(secondStructure)).thenReturn(Optional.of(
				new OrganizationalStructure(secondStructure, "Seconde structure", tenantID)));
		when(transfers.lockAccounts(outgoingID, successorID)).thenReturn(
				Map.of(outgoingID, outgoing, successorID, successor));
		when(authorization.hasTenantMembership(outgoingID, tenantID)).thenReturn(true);
		when(authorization.hasTenantMembership(successorID, tenantID)).thenReturn(true);
		when(memberships.findStructureIDsByUser(outgoingID)).thenReturn(List.of(firstStructure, secondStructure));
		when(memberships.findStructureIDsByUser(successorID)).thenReturn(List.of(firstStructure));
		when(transfers.snapshot(outgoing, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(outgoing, List.of(
				new StructureMembership(outgoingID, firstStructure, Set.of(UserRole.ADMIN)),
				new StructureMembership(outgoingID, secondStructure, Set.of(UserRole.ADMIN)))));
		when(transfers.snapshot(successor, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(successor, List.of(
				new StructureMembership(successorID, firstStructure, Set.of(UserRole.EMPLOYEE)))));
		when(tenants.findByIdForUpdate(tenantID)).thenReturn(Optional.of(
				new TenantOrganization(tenantID, "Entreprise test", outgoingID)));
		when(sessionProvider.getCurrentSessionIDHash()).thenReturn(outgoingSession);
		when(verifier.consumeRecentProof(eq(outgoingID), eq(outgoingSession), eq(2L),
				eq(Action.SUPER_ADMIN_SUCCESSION_CREATE), anyString(), eq("creation-proof"))).thenReturn(true);
	}

	@Test
	void creatingAnOfferStoresTheConfirmedTwentyFourHourProposalWithoutTransferringAnyRights() {
		SuccessionPreview preview = preview(Set.of(firstStructure));

		SuccessionOffer offer = service.createSuccessionOffer(preview, "creation-proof");

		ArgumentCaptor<SuccessionOffer> stored = ArgumentCaptor.forClass(SuccessionOffer.class);
		verify(offerStore).create(stored.capture());
		assertEquals(offer, stored.getValue());
		assertEquals(tenantID, offer.tenantID());
		assertEquals(outgoingID, offer.outgoingUserID());
		assertEquals(successorID, offer.successorUserID());
		assertEquals(List.of(firstStructure), offer.adminRolesToTransfer());
		assertEquals(preview.confirmationDigest(), offer.previewDigest());
		assertEquals(SuccessionOffer.OfferStatus.PENDING, offer.status());
		assertEquals(clock.instant(), offer.createdAt());
		assertEquals(clock.instant().plusSeconds(86400), offer.expiresAt());
		verify(tenants, never()).replaceSuperAdmin(any(), any(), any());
		verify(transfers, never()).moveAdminRole(any(), any(), any(), any(), any(), any());
		verifyNoInteractions(lifecycle);
	}

	@Test
	void onlyTheOutgoingSuperAdminCanCreateAnOffer() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(authenticated.getCurrentAccount()).thenReturn(successor);
		assertThrows(AccessDeniedException.class, () -> service.createSuccessionOffer(preview, "creation-proof"));
		verifyNoInteractions(offerStore, lifecycle);
	}

	@Test
	void aStaleOutgoingSessionCannotCreateAnOffer() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(transfers.lockAccounts(outgoingID, successorID)).thenReturn(Map.of(outgoingID,
				account(outgoingID, UserAccountStatus.ACTIVE, 3), successorID, successor));
		assertThrows(AccessDeniedException.class, () -> service.createSuccessionOffer(preview, "creation-proof"));
		verifyNoInteractions(offerStore, lifecycle);
	}

	@Test
	void aDisabledSuccessorCannotReceiveANewOffer() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(transfers.lockAccounts(outgoingID, successorID)).thenReturn(Map.of(outgoingID, outgoing,
				successorID, account(successorID, UserAccountStatus.DISABLED, 7)));
		assertThrows(AccessDeniedException.class, () -> service.createSuccessionOffer(preview, "creation-proof"));
		verifyNoInteractions(offerStore, lifecycle);
	}

	@Test
	void creatingAnOfferRequiresTheSameConfirmedPreview() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(transfers.snapshot(outgoing, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(outgoing,
				List.of(new StructureMembership(outgoingID, firstStructure, Set.of(UserRole.ADMIN, UserRole.EMPLOYEE)),
						new StructureMembership(outgoingID, secondStructure, Set.of(UserRole.ADMIN)))));
		assertThrows(AccessDeniedException.class, () -> service.createSuccessionOffer(preview, "creation-proof"));
		verifyNoInteractions(offerStore, lifecycle);
	}

	@Test
	void onlyTheSuccessorCanFinalizeTheConfirmedOffer() {
		UUID offerID = prepareFinalization(preview(Set.of(firstStructure)));
		when(authenticated.getCurrentAccount()).thenReturn(outgoing);
		assertThrows(AccessDeniedException.class, () -> service.finalizeSuccession(offerID));
		assertNoChanges();
	}

	@Test
	void refusesAFinalizationFromAnotherSessionOfTheSameSuccessor() {
		UUID offerID = prepareFinalization(preview(Set.of(firstStructure)));
		when(sessionProvider.getCurrentSessionIDHash()).thenReturn("3".repeat(64));
		assertThrows(AccessDeniedException.class, () -> service.finalizeSuccession(offerID));
		assertNoChanges();
		verifyNoInteractions(sessionValidityVerifier);
	}

	@Test
	void refusesAConfirmationBelongingToAnotherOffer() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		UUID offerID = prepareFinalization(preview);
		when(confirmationStore.findByOfferIDAndUserID(offerID, outgoingID)).thenReturn(Optional.of(
				confirmation(UUID.randomUUID(), outgoing, outgoingSession, preview, clock.instant().plusSeconds(300))));
		assertThrows(AccessDeniedException.class, () -> service.finalizeSuccession(offerID));
		assertNoChanges();
	}

	@Test
	void expiresTheConfirmationAtTheExactSharedAuthorizationInstant() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		UUID offerID = prepareFinalization(preview);
		when(sessionValidityVerifier.validateTogether(outgoingID, 2L, outgoingSession, successorID, 7L,
				successorSession)).thenReturn(Optional.of(clock.instant().plusSeconds(300)));
		assertThrows(AccessDeniedException.class, () -> service.finalizeSuccession(offerID));
		assertNoChanges();
	}

	@Test
	void neverTransfersWhenTheJointSessionCheckIsUnavailable() {
		UUID offerID = prepareFinalization(preview(Set.of(firstStructure)));
		when(sessionValidityVerifier.validateTogether(outgoingID, 2L, outgoingSession, successorID, 7L,
				successorSession)).thenThrow(new fr.gestionplus.server.auth.DistributedSecurityUnavailableException());
		assertThrows(fr.gestionplus.server.auth.DistributedSecurityUnavailableException.class,
				() -> service.finalizeSuccession(offerID));
		assertNoChanges();
	}

	@Test
	void transfersOnlyTheExplicitlyChosenLocalRolesAndRevokesBothSessions() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		UUID offerID = prepareFinalization(preview);

		service.finalizeSuccession(offerID);

		assertFalse(preview.outgoingWillBeDisabled());
		verify(offerStore).complete(offerID, clock.instant());
		verify(confirmationStore).deleteByOfferID(offerID);
		verify(tenants).replaceSuperAdmin(tenantID, outgoingID, successorID);
		verify(transfers).moveAdminRole(successorID, firstStructure, outgoingID, Set.of(), successorID,
				Set.of(UserRole.EMPLOYEE));
		verify(transfers, never()).moveAdminRole(any(), eq(secondStructure), any(), any(), any(), any());
		verify(lifecycle).invalidateSessions(eq(outgoing), any());
		verify(lifecycle).invalidateSessions(eq(successor), any());
		verify(lifecycle, never()).disable(any(), any());
		verify(audit).record(eq(AuditAction.SUPER_ADMIN_SUCCESSION), eq(AuditResult.SUCCESS), any());
	}

	@Test
	void transferringEveryRemainingLocalRoleDisablesOnlyTheOutgoingAccount() {
		SuccessionPreview preview = preview(Set.of(firstStructure, secondStructure));
		UUID offerID = prepareFinalization(preview);

		service.finalizeSuccession(offerID);

		assertTrue(preview.outgoingWillBeDisabled());
		verify(lifecycle).disable(outgoing, preview.adminRolesToTransfer().getFirst());
		verify(lifecycle).invalidateSessions(eq(successor), any());
		verify(lifecycle, never()).invalidateSessions(eq(outgoing), any());
	}

	@Test
	void aFailedConditionalReplacementCannotChangeLocalRoles() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		UUID offerID = prepareFinalization(preview);
		when(tenants.replaceSuperAdmin(tenantID, outgoingID, successorID)).thenReturn(false);

		assertThrows(AccessDeniedException.class,
				() -> service.finalizeSuccession(offerID));
		verify(transfers, never()).moveAdminRole(any(), any(), any(), any(), any(), any());
		verifyNoInteractions(lifecycle, audit);
	}

	@Test
	void refusesAStaleSuccessorSessionAndAChangedRoleSnapshot() {
		UUID offerID = prepareFinalization(preview(Set.of(firstStructure)));
		when(authenticated.getCurrentAccount()).thenReturn(account(successorID, UserAccountStatus.ACTIVE, 8));
		assertThrows(AccessDeniedException.class,
				() -> service.finalizeSuccession(offerID));
		when(authenticated.getCurrentAccount()).thenReturn(successor);
		when(transfers.snapshot(outgoing, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(outgoing, List.of(
				new StructureMembership(outgoingID, firstStructure, Set.of(UserRole.ADMIN, UserRole.EMPLOYEE)),
				new StructureMembership(outgoingID, secondStructure, Set.of(UserRole.ADMIN)))));
		assertThrows(AccessDeniedException.class,
				() -> service.finalizeSuccession(offerID));
		assertNoChanges();
		verifyNoInteractions(verifier);
	}

	@Test
	void refusesAStaleOutgoingSession() {
		when(authenticated.getCurrentAccount()).thenReturn(account(outgoingID, UserAccountStatus.ACTIVE, 1));
		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		assertNoChanges();
	}

	@Test
	void refusesCrossTenantAndAlreadyDesignatedSuccessors() {
		when(authorization.hasTenantMembership(successorID, tenantID)).thenReturn(false);
		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		when(authorization.hasTenantMembership(successorID, tenantID)).thenReturn(true);
		when(authorization.isDesignatedSuperAdmin(successorID)).thenReturn(true);
		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		assertNoChanges();
	}

	@Test
	void refusesAnInactiveSuccessorAndAnIncorrectTenantHolder() {
		when(transfers.lockAccounts(outgoingID, successorID)).thenReturn(Map.of(outgoingID, outgoing, successorID,
				account(successorID, UserAccountStatus.DISABLED, 7)));
		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		when(transfers.lockAccounts(outgoingID, successorID)).thenReturn(Map.of(outgoingID, outgoing,
				successorID, successor));
		when(tenants.findById(tenantID)).thenReturn(Optional.of(
				new TenantOrganization(tenantID, "Entreprise test", UUID.randomUUID())));
		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		assertNoChanges();
	}

	@Test
	void refusesForeignMembershipsAndUnownedAdminTransfers() {
		when(memberships.findStructureIDsByUser(successorID)).thenReturn(List.of(UUID.randomUUID()));
		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		when(memberships.findStructureIDsByUser(successorID)).thenReturn(List.of(firstStructure));
		assertThrows(AccessDeniedException.class, () -> preview(Set.of(UUID.randomUUID())));
		assertNoChanges();
	}

	@Test
	void refusesLegacyGlobalSuperAdminRolesInsideAMembership() {
		when(transfers.snapshot(successor, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(successor, List.of(
				new StructureMembership(successorID, firstStructure, Set.of(UserRole.SUPER_ADMIN)))));
		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		assertNoChanges();
	}

	@Test
	void refusesAChangedStructureListBeforePreparingThePreview() {
		when(structures.findIDsByTenant(tenantID)).thenReturn(List.of(firstStructure, secondStructure),
				List.of(firstStructure));

		assertThrows(AccessDeniedException.class, () -> preview(Set.of()));
		assertNoChanges();
	}

	private UUID prepareFinalization(SuccessionPreview preview) {
		UUID offerID = UUID.randomUUID();
		SuccessionOffer offer = new SuccessionOffer(offerID, outgoingID, successorID, tenantID,
				preview.adminRolesToTransfer(), preview.confirmationDigest(), SuccessionOffer.OfferStatus.ACCEPTED,
				clock.instant(), clock.instant().plusSeconds(86400));
		when(offerStore.findById(offerID)).thenReturn(Optional.of(offer));
		when(offerStore.findByIdForUpdate(offerID)).thenReturn(Optional.of(offer));
		when(authenticated.getCurrentAccount()).thenReturn(successor);
		when(sessionProvider.getCurrentSessionIDHash()).thenReturn(successorSession);
		when(confirmationStore.findByOfferIDAndUserID(offerID, outgoingID)).thenReturn(Optional.of(
				confirmation(offerID, outgoing, outgoingSession, preview, clock.instant().plusSeconds(300))));
		when(confirmationStore.findByOfferIDAndUserID(offerID, successorID)).thenReturn(Optional.of(
				confirmation(offerID, successor, successorSession, preview, clock.instant().plusSeconds(300))));
		when(sessionValidityVerifier.validateTogether(outgoingID, 2L, outgoingSession, successorID, 7L,
				successorSession)).thenReturn(Optional.of(clock.instant()));
		when(offerStore.complete(offerID, clock.instant())).thenReturn(true);
		return offerID;
	}

	private SuccessionConfirmation confirmation(UUID offerID, UserAccount user, String sessionHash,
			SuccessionPreview preview, Instant expiresAt) {
		return new SuccessionConfirmation(offerID, user.getUserID(), user.getSessionVersion(), sessionHash,
				preview.confirmationDigest(), clock.instant(), expiresAt);
	}

	private SuccessionPreview preview(Set<UUID> transferred) {
		return service.previewSuccession(tenantID, successorID, transferred);
	}

	private void assertNoChanges() {
		verify(tenants, never()).replaceSuperAdmin(any(), any(), any());
		verify(transfers, never()).moveAdminRole(any(), any(), any(), any(), any(), any());
		verifyNoInteractions(lifecycle, audit);
	}

	private UserAccount account(UUID userID, UserAccountStatus status, long version) {
		return new UserAccount(userID, "test_" + userID, status, Set.of(), UUID.randomUUID(), "Exemple", "Test",
				"test@example.invalid", "+12025550100", version);
	}

	@Test
	void disablesAnOutgoingHolderWithoutAnyLocalRole() {
		when(memberships.findStructureIDsByUser(outgoingID)).thenReturn(List.of());
		when(transfers.snapshot(outgoing, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(outgoing, List.of()));
		SuccessionPreview preview = preview(Set.of());
		UUID offerID = prepareFinalization(preview);

		service.finalizeSuccession(offerID);

		assertTrue(preview.outgoingWillBeDisabled());
		verify(lifecycle).disable(outgoing, null);
		verify(lifecycle, never()).invalidateSessions(eq(outgoing), any());
		verify(lifecycle).invalidateSessions(eq(successor), any());
	}
}
