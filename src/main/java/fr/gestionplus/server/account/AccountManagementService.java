package fr.gestionplus.server.account;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.RoleHierarchyPolicy;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import jakarta.transaction.Transactional;

@Service
public class AccountManagementService {

	private final AccountStore store;
	private final AuthorizationService authorizationService;
	private final StructureMembershipStore structureMembershipStore;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final RoleHierarchyPolicy roleHierarchyPolicy;
	private final AuditService auditService;
	private final OrganizationalStructureStore organizationalStructureStore;

	public AccountManagementService(AuthorizationService authorizationService, AccountStore store,
			StructureMembershipStore structureMembershipStore, AuthenticatedUserProvider authenticatedUserProvider,
			AuditService auditService, RoleHierarchyPolicy roleHierarchyPolicy,
			OrganizationalStructureStore organizationalStructureStore) {

		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.store = Objects.requireNonNull(store);
		this.structureMembershipStore = Objects.requireNonNull(structureMembershipStore);
		this.authenticatedUserProvider = Objects.requireNonNull(authenticatedUserProvider);
		this.roleHierarchyPolicy = Objects.requireNonNull(roleHierarchyPolicy);
		this.auditService = Objects.requireNonNull(auditService);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
	}

	@Transactional
	public void revokeStructureAccess(UUID targetUserId, UUID structureID) {

		UUID actorUserId = authenticatedUserProvider.getCurrentUserID();

		if (actorUserId == null || targetUserId == null || structureID == null)
			throw new NullPointerException();

		if (actorUserId.equals(targetUserId))
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<OrganizationalStructure> structure = organizationalStructureStore.findByIdForUpdate(structureID);

		if (structure.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		if (!authorizationService.canInStructure(actorUserId, structureID, Permission.MANAGE_USERS))
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<StructureMembership> targetStructureMembership = structureMembershipStore
				.findByUserAndStructure(targetUserId, structureID);

		if (targetStructureMembership.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<UserAccount> targetAccount = store.findById(targetUserId);

		if (targetAccount.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		if (targetStructureMembership.get().getRoles().contains(UserRole.ADMIN)
				|| targetAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<UserAccount> actorAccount = store.findById(actorUserId);

		if (actorAccount.isEmpty())
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		Optional<StructureMembership> actorStructureMembership = structureMembershipStore
				.findByUserAndStructure(actorUserId, structureID);

		if (actorStructureMembership.isEmpty() && !actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException("Vous ne paserez paaaaas !!!");

		if (!actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN)) {

			boolean allow = roleHierarchyPolicy.isStrictlyHigher(actorStructureMembership.get().getRoles(),
					targetStructureMembership.get().getRoles());

			if (!allow)
				throw new AccessDeniedException("Vous ne paserez paaaaas !!!");
		}

		structureMembershipStore.deleteByUserAndStructure(targetUserId, structureID);

		AuditTarget userTarget = new AuditTarget(AuditTargetType.ACCOUNT, targetUserId);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);
		Set<AuditTarget> targets = Set.of(userTarget, structureTarget);

		auditService.record(AuditAction.STRUCTURE_ACCESS_REVOKE, AuditResult.SUCCESS, targets);
	}
}
