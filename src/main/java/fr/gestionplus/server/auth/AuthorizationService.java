package fr.gestionplus.server.auth;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Service
public class AuthorizationService {

	private final StructureMembershipStore structureMembershipStore;
	private final AccountStore accountStore;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantOrganizationStore tenantOrganizationStore;

	public AuthorizationService(StructureMembershipStore structureMembershipStore, AccountStore accountStore,
			OrganizationalStructureStore organizationalStructureStore,
			TenantOrganizationStore tenantOrganizationStore) {
		this.structureMembershipStore = Objects.requireNonNull(structureMembershipStore);
		this.accountStore = Objects.requireNonNull(accountStore);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.tenantOrganizationStore = Objects.requireNonNull(tenantOrganizationStore);
	}

	public boolean canInStructure(UUID actorUserID, UUID structureID, Permission permission) {

		if (actorUserID == null || structureID == null || permission == null)
			return false;

		Optional<UserAccount> account = accountStore.findById(actorUserID);

		if (account.isEmpty())
			return false;

		UserAccount userAccount = account.get();

		if (userAccount.getStatu() != UserAccountStatus.ACTIVE)
			return false;

		Optional<OrganizationalStructure> structure = organizationalStructureStore.findById(structureID);

		if (structure.isEmpty())
			return false;

		UUID tenantID = structure.get().getTenantID();
		Optional<TenantOrganization> tenantOrganization = tenantOrganizationStore.findById(tenantID);

		if (tenantOrganization.isEmpty())
			return false;

		if (userAccount.getRoles().contains(UserRole.SUPER_ADMIN)) {

			if (!tenantOrganization.get().getSuperAdminUserID().equals(actorUserID))
				return false;

			return RolePermissionPolicy.allows(UserRole.SUPER_ADMIN, permission);
		}

		Optional<StructureMembership> userAndStructure = structureMembershipStore.findByUserAndStructure(actorUserID,
				structureID);

		if (userAndStructure.isEmpty())
			return false;

		if (userAndStructure.get().getRoles().contains(UserRole.SUPER_ADMIN))
			return false;

		for (UserRole role : userAndStructure.get().getRoles()) {

			if (RolePermissionPolicy.allows(role, permission))
				return true;
		}

		return false;
	}
}
