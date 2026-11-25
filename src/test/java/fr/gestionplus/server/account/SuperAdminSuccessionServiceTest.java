package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.auth.AccountMfaService;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

class SuperAdminSuccessionServiceTest {

	@Test
	void unfinishedCycleCannotMutateOrExposeAnOffer() {
		var authenticated = mock(AuthenticatedUserProvider.class);
		var scope = mock(SuccessionScope.class);
		var security = mock(SuccessionSecurity.class);
		var transaction = mock(SuccessionTransaction.class);
		var offers = mock(SuccessionOfferStore.class);
		var revisions = mock(SuccessionRevisionStore.class);
		var consents = mock(SuccessionConsentStore.class);
		var confirmations = mock(SuccessionConfirmationStore.class);
		var mfa = mock(AccountMfaService.class);
		var transfer = mock(SuccessionTransfer.class);
		var clock = mock(Clock.class);
		var service = new SuperAdminSuccessionService(authenticated, scope, security, transaction, offers, revisions,
				consents, confirmations, mfa, transfer, clock);
		UUID offerID = UUID.randomUUID();

		assertThrows(AccessDeniedException.class, () -> service.refreshSuccessionPreview(offerID));
		assertThrows(AccessDeniedException.class, () -> service.createSuccessionOffer(null, null));
		assertThrows(AccessDeniedException.class, () -> service.acceptSuccessionOffer(offerID, 1));
		assertThrows(AccessDeniedException.class, () -> service.confirmSuccession(offerID, 1, true, "test", "000000"));
		assertThrows(AccessDeniedException.class, () -> service.withdrawSuccessionOffer(offerID));
		assertThrows(AccessDeniedException.class, () -> service.cancelSuccessionOffer(offerID));
		assertThrows(AccessDeniedException.class, () -> service.finalizeSuccession(offerID, 1));
		assertThrows(AccessDeniedException.class, () -> service.getSuccessionStatus(offerID));
		assertThrows(AccessDeniedException.class, () -> service.updateAdminChoices(offerID, 1, Set.of()));

		verifyNoInteractions(authenticated, scope, security, transaction, offers, revisions, consents, confirmations, mfa, transfer, clock);
	}

	@Test
	void refreshedProjectionDependsOnRolesAndMembershipsAndIgnoresSecurityVersions() {
		var scope = new SuccessionScope(null, null, null, null, null, null);
		var tenant = UUID.randomUUID();
		var outgoing = UUID.randomUUID();
		var successor = UUID.randomUUID();
		var structure = UUID.randomUUID();
		var current = snapshot(tenant, outgoing, successor, structure, 2,
				List.of(UserRole.ADMIN, UserRole.EMPLOYEE));
		var nextSession = snapshot(tenant, outgoing, successor, structure, 9,
				List.of(UserRole.EMPLOYEE, UserRole.ADMIN));
		var reassigned = snapshot(tenant, outgoing, successor, structure, 9,
				List.of(UserRole.EMPLOYEE));
		var first = scope.preview(current, Set.of(structure));
		var same = scope.preview(nextSession, Set.of(structure));

		assertEquals(first, same);
		assertEquals(first.confirmationDigest(), same.confirmationDigest());
		var additionalStructure = new SuccessionScope.Locked(tenant, List.of(structure, UUID.randomUUID()),
				current.outgoing(), current.successor(), current.outgoingSnapshot(), current.successorSnapshot(),
				SuccessionScope.Eligibility.ELIGIBLE);
		assertEquals(first, scope.preview(additionalStructure, Set.of(structure)));
		assertEquals(Set.of(UserRole.EMPLOYEE), first.outgoing().after().rolesIn(structure));
		assertEquals(Set.of(UserRole.ADMIN), first.successor().after().rolesIn(structure));
		assertEquals(Set.of(structure), scope.unavailableAdminChoices(reassigned, Set.of(structure)));
		assertThrows(AccessDeniedException.class, () -> scope.preview(reassigned, Set.of(structure)));
	}

	private static SuccessionScope.Locked snapshot(UUID tenant, UUID outgoing, UUID successor, UUID structure,
			long version, List<UserRole> roles) {
		var old = mock(UserAccount.class);
		var next = mock(UserAccount.class);
		when(old.getUserID()).thenReturn(outgoing);
		when(next.getUserID()).thenReturn(successor);
		var active = UserAccountStatus.ACTIVE;
		var before = new PrivilegedAccountSnapshot(outgoing, active, version,
				List.of(new PrivilegedAccountSnapshot.LocalRoles(structure, roles)));
		var after = new PrivilegedAccountSnapshot(successor, active, version, List.of());
		return new SuccessionScope.Locked(tenant, List.of(structure), old, next, before, after,
				SuccessionScope.Eligibility.ELIGIBLE);
	}
}
