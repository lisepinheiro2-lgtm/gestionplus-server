package fr.gestionplus.server.account;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
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
import fr.gestionplus.server.auth.UserAccountLookup;
import fr.gestionplus.server.organization.OrganizationalStructure;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.TenantMembership;
import fr.gestionplus.server.organization.TenantMembershipStore;

@Service
public class AccountProvisioningService {

	private final AccountStore store;
	private final PasswordEncoder encoder;
	private final UserAccountLookup accountLookup;
	private final AuditService auditService;
	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final AuthorizationService authorizationService;
	private final OrganizationalStructureStore organizationalStructureStore;
	private final TenantMembershipStore tenantMembershipStore;

	public AccountProvisioningService(AccountStore store, PasswordEncoder encoder, UserAccountLookup accountLookup,
			AuditService auditService, AuthenticatedUserProvider authenticatedUserProvider,
			AuthorizationService authorizationService, OrganizationalStructureStore organizationalStructureStore,
			TenantMembershipStore tenantMembershipStore) {
		this.store = Objects.requireNonNull(store);
		this.encoder = Objects.requireNonNull(encoder);
		this.accountLookup = Objects.requireNonNull(accountLookup);
		this.auditService = Objects.requireNonNull(auditService);
		this.authenticatedUserProvider = Objects.requireNonNull(authenticatedUserProvider);
		this.authorizationService = Objects.requireNonNull(authorizationService);
		this.organizationalStructureStore = Objects.requireNonNull(organizationalStructureStore);
		this.tenantMembershipStore = Objects.requireNonNull(tenantMembershipStore);
	}

	@Transactional
	public UUID createPendingAccount(UUID structureID, String lastName, String firstName, String email,
			String phoneNumber) {

		UUID actorUserId = authenticatedUserProvider.getCurrentUserID();

		if (!authorizationService.canInStructure(actorUserId, structureID, Permission.MANAGE_USERS))
			throw new AccessDeniedException("Vous ne disposez pas des autorisations nécessaires.");

		Optional<OrganizationalStructure> structure = organizationalStructureStore.findById(structureID);

		if (structure.isEmpty())
			throw new AccessDeniedException("Vous ne disposez pas des autorisations nécessaires.");

		UUID tenantID = structure.get().getTenantID();
		UUID accountID = UUID.randomUUID();
		UserAccount account = UserAccount.pending(accountID, lastName, firstName, email, phoneNumber);
		TenantMembership membership = new TenantMembership(accountID, tenantID);

		store.createAccount(account);
		tenantMembershipStore.save(membership);

		AuditTarget userTarget = new AuditTarget(AuditTargetType.ACCOUNT, accountID);
		AuditTarget structureTarget = new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID);

		Set<AuditTarget> targets = Set.of(userTarget, structureTarget);

		auditService.record(AuditAction.ACCOUNT_CREATE, AuditResult.SUCCESS, targets);

		return accountID;
	}
}
