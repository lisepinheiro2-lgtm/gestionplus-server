package fr.gestionplus.server.account;

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

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.account.SuperAdminSuccessionService.SuccessionPreview;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
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
		service = new SuperAdminSuccessionService(authenticated, authorization, tenants, structures, memberships,
				transfers, lifecycle,
				new PrivilegedConfirmationService(beans.getBeanProvider(PrivilegedConfirmationVerifier.class)), audit);
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
		when(verifier.consumeRecentProof(eq(outgoingID), eq(2L), eq(Action.SUPER_ADMIN_SUCCESSION_OFFER),
				anyString(), eq("offer-proof"))).thenReturn(true);
		when(verifier.consumeRecentProof(eq(successorID), eq(7L), eq(Action.SUPER_ADMIN_SUCCESSION_ACCEPTANCE),
				anyString(), eq("accept-proof"))).thenReturn(true);
	}

	@Test
	void onlyTheSuccessorCanAcceptTheConfirmedSuccession() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		assertThrows(AccessDeniedException.class,
				() -> service.acceptSuccession(preview, "offer-proof", "accept-proof"));
		assertNoChanges();
	}

	@Test
	void requiresBothRecentProofsForTheExactPreview() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(authenticated.getCurrentAccount()).thenReturn(successor);

		assertThrows(AccessDeniedException.class, () -> service.acceptSuccession(preview, null, "accept-proof"));
		assertThrows(AccessDeniedException.class,
				() -> service.acceptSuccession(preview, "offer-proof", "wrong-proof"));
		assertNoChanges();
	}

	@Test
	void transfersOnlyTheExplicitlyChosenLocalRolesAndRevokesBothSessions() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(authenticated.getCurrentAccount()).thenReturn(successor);

		service.acceptSuccession(preview, "offer-proof", "accept-proof");

		assertFalse(preview.outgoingWillBeDisabled());
		verify(verifier).consumeRecentProof(outgoingID, 2L, Action.SUPER_ADMIN_SUCCESSION_OFFER,
				preview.confirmationDigest(), "offer-proof");
		verify(verifier).consumeRecentProof(successorID, 7L, Action.SUPER_ADMIN_SUCCESSION_ACCEPTANCE,
				preview.confirmationDigest(), "accept-proof");
		verify(tenants).replaceSuperAdmin(tenantID, outgoingID, successorID);
		verify(transfers).moveAdminRole(firstStructure, outgoingID, Set.of(), successorID, Set.of(UserRole.EMPLOYEE));
		verify(transfers, never()).moveAdminRole(eq(secondStructure), any(), any(), any(), any());
		verify(lifecycle).invalidateSessions(eq(outgoing), any());
		verify(lifecycle).invalidateSessions(eq(successor), any());
		verify(lifecycle, never()).disable(any(), any());
		verify(audit).record(eq(AuditAction.SUPER_ADMIN_SUCCESSION), eq(AuditResult.SUCCESS), any());
	}

	@Test
	void transferringEveryRemainingLocalRoleDisablesOnlyTheOutgoingAccount() {
		SuccessionPreview preview = preview(Set.of(firstStructure, secondStructure));
		when(authenticated.getCurrentAccount()).thenReturn(successor);

		service.acceptSuccession(preview, "offer-proof", "accept-proof");

		assertTrue(preview.outgoingWillBeDisabled());
		verify(lifecycle).disable(outgoing, preview.adminRolesToTransfer().getFirst());
		verify(lifecycle).invalidateSessions(eq(successor), any());
		verify(lifecycle, never()).invalidateSessions(eq(outgoing), any());
	}

	@Test
	void aFailedConditionalReplacementCannotChangeLocalRoles() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(authenticated.getCurrentAccount()).thenReturn(successor);
		when(tenants.replaceSuperAdmin(tenantID, outgoingID, successorID)).thenReturn(false);

		assertThrows(AccessDeniedException.class,
				() -> service.acceptSuccession(preview, "offer-proof", "accept-proof"));
		verify(transfers, never()).moveAdminRole(any(), any(), any(), any(), any());
		verifyNoInteractions(lifecycle, audit);
	}

	@Test
	void refusesAStaleSuccessorSessionAndAChangedRoleSnapshot() {
		SuccessionPreview preview = preview(Set.of(firstStructure));
		when(authenticated.getCurrentAccount()).thenReturn(account(successorID, UserAccountStatus.ACTIVE, 8));
		assertThrows(AccessDeniedException.class,
				() -> service.acceptSuccession(preview, "offer-proof", "accept-proof"));
		when(authenticated.getCurrentAccount()).thenReturn(successor);
		when(transfers.snapshot(outgoing, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(outgoing, List.of(
				new StructureMembership(outgoingID, firstStructure, Set.of(UserRole.ADMIN, UserRole.EMPLOYEE)),
				new StructureMembership(outgoingID, secondStructure, Set.of(UserRole.ADMIN)))));
		assertThrows(AccessDeniedException.class,
				() -> service.acceptSuccession(preview, "offer-proof", "accept-proof"));
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

	private SuccessionPreview preview(Set<UUID> transferred) {
		return service.previewSuccession(tenantID, successorID, transferred);
	}

	private void assertNoChanges() {
		verify(tenants, never()).replaceSuperAdmin(any(), any(), any());
		verify(transfers, never()).moveAdminRole(any(), any(), any(), any(), any());
		verifyNoInteractions(lifecycle, audit);
	}

	private UserAccount account(UUID userID, UserAccountStatus status, long version) {
		return new UserAccount(userID, "test_" + userID, status, Set.of(), UUID.randomUUID(), "Exemple", "Test",
				"test@example.invalid", "0600000000", version);
	}

	@Test
	void disablesAnOutgoingHolderWithoutAnyLocalRole() {
		when(memberships.findStructureIDsByUser(outgoingID)).thenReturn(List.of());
		when(transfers.snapshot(outgoing, tenantID)).thenReturn(PrivilegedAccountSnapshot.capture(outgoing, List.of()));
		SuccessionPreview preview = preview(Set.of());
		when(authenticated.getCurrentAccount()).thenReturn(successor);

		service.acceptSuccession(preview, "offer-proof", "accept-proof");

		assertTrue(preview.outgoingWillBeDisabled());
		verify(lifecycle).disable(outgoing, null);
		verify(lifecycle, never()).invalidateSessions(eq(outgoing), any());
		verify(lifecycle).invalidateSessions(eq(successor), any());
	}
}
