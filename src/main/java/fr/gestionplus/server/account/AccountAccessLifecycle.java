package fr.gestionplus.server.account;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.SessionRevocationService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;

@Service
public class AccountAccessLifecycle {

	private final AccountStore store;
	private final StructureMembershipStore membershipStore;
	private final OrganizationalStructureStore structureStore;
	private final AuthorizationService authorizationService;
	private final SessionRevocationService sessionRevocationService;
	private final AuditService auditService;
	private final AccountDeactivationNotifications notifications;

	public AccountAccessLifecycle(AccountStore store, StructureMembershipStore membershipStore,
			OrganizationalStructureStore structureStore, AuthorizationService authorizationService,
			SessionRevocationService sessionRevocationService, AuditService auditService,
			AccountDeactivationNotifications notifications) {
		this.store = store;
		this.membershipStore = membershipStore;
		this.structureStore = structureStore;
		this.authorizationService = authorizationService;
		this.sessionRevocationService = sessionRevocationService;
		this.auditService = auditService;
		this.notifications = notifications;
	}

	void disableIfWithoutAccess(UserAccount account, UUID actorUserID, UUID structureID) {

		if (authorizationService.isDesignatedSuperAdmin(account.getUserID())
				|| account.getStatu() == UserAccountStatus.DISABLED) {
			return;
		}

		if (hasValidAccessOutside(account, null)) {
			return;
		}

		disable(account, structureID);

		if (account.getStatu() == UserAccountStatus.PENDING_ACTIVATION) {
			notifications.enqueue(actorUserID, account.getUserID(), structureID);
		}

	}

	boolean hasValidAccessOutside(UserAccount account, UUID excludedStructureID) {

		UUID userID = account.getUserID();
		List<StructureMembership> remainingMemberships = membershipStore.findByUser(userID);

		for (StructureMembership membership : remainingMemberships) {

			if (!membership.getStructureID().equals(excludedStructureID) && !membership.getRoles().isEmpty()
					&& !membership.getRoles().contains(UserRole.SUPER_ADMIN)
					&& structureStore.findById(membership.getStructureID()).filter(
							structure -> authorizationService.hasTenantMembership(userID, structure.getTenantID()))
							.isPresent()) {
				return true;
			}

		}

		return false;
	}

	void invalidateSessions(UserAccount account, AuditTarget scope) {
		store.advanceSessionVersion(account.getUserID());
		revokeSessions(account, scope);
	}

	void disable(UserAccount account, UUID structureID) {
		UUID userID = account.getUserID();
		store.disableAccount(userID);
		auditService.record(AuditAction.ACCOUNT_DISABLE, AuditResult.SUCCESS,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, userID),
						new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID)));

		revokeSessions(account, new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
	}

	private void revokeSessions(UserAccount account, AuditTarget scope) {
		auditService.record(AuditAction.AUTH_SESSION_REVOKE, AuditResult.SUCCESS,
				Set.of(new AuditTarget(AuditTargetType.ACCOUNT, account.getUserID()), scope));
		sessionRevocationService.revokeAllSessionsAfterCommit(account);
	}
}
