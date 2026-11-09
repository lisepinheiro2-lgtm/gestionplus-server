package fr.gestionplus.server.account;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantMembershipStore;

@Service
public class StructureAdminTransferService {

	private final AccountStore store;
	private final AuthorizationService authorizationService;
	private final StructureMembershipStore structureMembershipStore;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AuditService auditService;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantMembershipStore tenantMembershipStore;

	public StructureAdminTransferService(AuthorizationService authorizationService, AccountStore store,
			StructureMembershipStore structureMembershipStore, AuthenticatedUserProvider authenticatedUserProvider,
			AuditService auditService, OrganizationalStructureStore organizationalStructureStore,
			TenantMembershipStore tenantMembershipStore) {

		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.store = Objects.requireNonNull(store);
		this.structureMembershipStore = Objects.requireNonNull(structureMembershipStore);
		this.authenticatedUserProvider = Objects.requireNonNull(authenticatedUserProvider);
		this.auditService = Objects.requireNonNull(auditService);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.tenantMembershipStore = Objects.requireNonNull(tenantMembershipStore);
	}

	@Transactional
	public void transferStructureAdmin(UUID structureID, UUID newAdminUserID) {

		UUID actorID = authenticatedUserProvider.getCurrentUserID();

		if (structureID == null || newAdminUserID == null)
			throw new AccessDeniedException("Identifiants non reconnus.");

		Optional<OrganizationalStructure> targetedStructure = organizationalStructureStore
				.findByIdForUpdate(structureID);

		if (targetedStructure.isEmpty())
			throw new AccessDeniedException("Cette structure n'existe pas.");

		requireAuthorizedSuperAdmin(actorID, structureID);

		Optional<StructureMembership> oldAdminMembership = structureMembershipStore.findAdminByStructure(structureID);

		if (oldAdminMembership.isEmpty())
			throw new AccessDeniedException("Aucun administrateur actuel trouvé pour cette structure.");

		UUID tenantID = targetedStructure.get().getTenantID();
		requireTenantMembership(newAdminUserID, tenantID);
		requireActiveAccount(newAdminUserID);

		UUID oldAdminUserID = oldAdminMembership.get().getUserID();

		if (newAdminUserID.equals(oldAdminUserID))
			throw new AccessDeniedException("Cette personne est déjà l'administrateur de cette structure.");

		Set<UserRole> remainingRoles = new HashSet<>(oldAdminMembership.get().getRoles());
		remainingRoles.remove(UserRole.ADMIN);

		if (remainingRoles.isEmpty()) {
			structureMembershipStore.deleteByUserAndStructure(oldAdminUserID, structureID);
		} else {
			structureMembershipStore.saveAndFlush(new StructureMembership(oldAdminUserID, structureID, remainingRoles));
		}

		Set<UserRole> newAdminRoles = new HashSet<>(
				structureMembershipStore.findByUserAndStructure(newAdminUserID, structureID)
						.map(StructureMembership::getRoles).orElse(Set.of()));
		newAdminRoles.add(UserRole.ADMIN);
		structureMembershipStore.save(new StructureMembership(newAdminUserID, structureID, newAdminRoles));

		AuditTarget oldAdminTarget = new AuditTarget(AuditTargetType.ACCOUNT, oldAdminUserID);
		AuditTarget newAdminTarget = new AuditTarget(AuditTargetType.ACCOUNT, newAdminUserID);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		Set<AuditTarget> targets = Set.of(oldAdminTarget, newAdminTarget, structureTarget);

		auditService.record(AuditAction.STRUCTURE_ADMIN_TRANSFER, AuditResult.SUCCESS, targets);
	}

	@Transactional(readOnly = true)
	public List<StructureMembership> findOtherAdminMemberships(UUID structureID, UUID newAdminUserID) {

		UUID actorID = authenticatedUserProvider.getCurrentUserID();

		if (actorID == null || newAdminUserID == null || structureID == null)
			throw new NullPointerException();

		Optional<OrganizationalStructure> targetedStructure = organizationalStructureStore.findById(structureID);

		if (targetedStructure.isEmpty())
			throw new AccessDeniedException("Cette structure n'existe pas.");

		requireAuthorizedSuperAdmin(actorID, structureID);

		UUID tenantID = targetedStructure.get().getTenantID();

		requireTenantMembership(newAdminUserID, tenantID);
		requireActiveAccount(newAdminUserID);

		List<StructureMembership> structureMembershipList = structureMembershipStore
				.findAdminMembershipsByUserAndTenant(newAdminUserID, tenantID);

		List<StructureMembership> structureMemberships = new ArrayList<>();

		for (StructureMembership membership : structureMembershipList) {

			if (!membership.getStructureID().equals(structureID))
				structureMemberships.add(membership);
		}

		return structureMemberships;
	}

	private void requireAuthorizedSuperAdmin(UUID actorID, UUID structureID) {

		Optional<UserAccount> actorAccount = store.findById(actorID);

		if (actorAccount.isEmpty() || actorAccount.get().getStatu() != UserAccountStatus.ACTIVE
				|| !actorAccount.get().getRoles().contains(UserRole.SUPER_ADMIN))
			throw new AccessDeniedException(
					"Vous ne disposez pas des autorisations nécessaires pour effectuer cette action.");

		boolean can = authorizationService.canInStructure(actorID, structureID, Permission.MANAGE_USERS);

		if (!can)
			throw new AccessDeniedException(
					"Vous ne disposez pas des autorisations nécessaires pour effectuer cette action.");
	}

	private void requireActiveAccount(UUID userID) {

		Optional<UserAccount> account = store.findById(userID);

		if (account.isEmpty() || account.get().getStatu() != UserAccountStatus.ACTIVE)
			throw new AccessDeniedException("Ce compte utilisateur n'existe pas ou n'est pas actif.");
	}

	private void requireTenantMembership(UUID userID, UUID tenantID) {

		if (tenantMembershipStore.findByUserAndTenant(userID, tenantID).isEmpty())
			throw new AccessDeniedException("Cette personne n'est pas rattachée à l'entreprise concernée.");
	}
}
