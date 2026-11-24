package fr.gestionplus.server.account;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.PrivilegedConfirmationVerifier.Action;
import fr.gestionplus.server.account.SuccessionOffer.OfferStatus;
import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditTarget;
import fr.gestionplus.server.audit.AuditTargetSource;
import fr.gestionplus.server.audit.AuditTargetType;
import fr.gestionplus.server.audit.AuditedOperation;
import fr.gestionplus.server.auth.AuthenticatedUserProvider;
import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;
import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganization;
import fr.gestionplus.server.organization.TenantOrganizationStore;

@Service
public class SuperAdminSuccessionService {

	private final AuthenticatedUserProvider authenticatedUserProvider;
	private final CurrentSessionProvider sessionProvider;
	private final SessionValidityVerifier sessionValidityVerifier;
	private final AuthorizationService authorizationService;
	private final TenantOrganizationStore tenantStore;
	private final OrganizationalStructureStore structureStore;
	private final StructureMembershipStore membershipStore;
	private final SuccessionOfferStore offerStore;
	private final AccountStore accountStore;
	private final SuccessionConfirmationStore confirmationStore;
	private final StructureAdminTransferService adminTransfer;
	private final AccountAccessLifecycle lifecycle;
	private final PrivilegedConfirmationService confirmations;
	private final AuditService auditService;
	private final Clock clock;

	public SuperAdminSuccessionService(AuthenticatedUserProvider authenticatedUserProvider,
			CurrentSessionProvider sessionProvider, SessionValidityVerifier sessionValidityVerifier,
			AuthorizationService authorizationService, TenantOrganizationStore tenantStore,
			OrganizationalStructureStore structureStore, StructureMembershipStore membershipStore,
			SuccessionOfferStore offerStore, AccountStore accountStore, SuccessionConfirmationStore confirmationStore,
			StructureAdminTransferService adminTransfer, AccountAccessLifecycle lifecycle,
			PrivilegedConfirmationService confirmations, AuditService auditService, Clock clock) {

		this.authenticatedUserProvider = authenticatedUserProvider;
		this.sessionProvider = sessionProvider;
		this.sessionValidityVerifier = sessionValidityVerifier;
		this.authorizationService = authorizationService;
		this.tenantStore = tenantStore;
		this.structureStore = structureStore;
		this.membershipStore = membershipStore;
		this.offerStore = offerStore;
		this.accountStore = accountStore;
		this.confirmationStore = confirmationStore;
		this.adminTransfer = adminTransfer;
		this.lifecycle = lifecycle;
		this.confirmations = confirmations;
		this.auditService = auditService;
		this.clock = clock;
	}

	@AuditedOperation(value = AuditAction.SUPER_ADMIN_SUCCESSION_PREVIEW, tenant = 0, account = 1)
	@Transactional
	public SuccessionPreview previewSuccession(UUID tenantID, UUID successorID, Set<UUID> adminRolesToTransfer) {
		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		SuccessionState state = load(tenantID, principal.getUserID(), successorID, Set.copyOf(adminRolesToTransfer));

		if (state.outgoing().getSessionVersion() != principal.getSessionVersion()) {
			throw denied();
		}

		return state.preview();
	}

	@Transactional
	public void acceptSuccessionOffer(UUID offerID) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();

		if (offerID == null) {
			throw denied();
		}

		SuccessionOffer offer = offerStore.findById(offerID)
				.filter(found -> found.successorUserID().equals(principal.getUserID()))
				.orElseThrow(SuperAdminSuccessionService::denied);

		SuccessionState state = load(offer.tenantID(), offer.outgoingUserID(),
				offer.successorUserID(), Set.copyOf(offer.adminRolesToTransfer()));

		String previewDigest = state.preview().confirmationDigest();

		if (state.successor().getSessionVersion() != principal.getSessionVersion()
				|| !previewDigest.equals(offer.previewDigest())) {
			throw denied();
		}

		lockCurrentOffer(offerID, state, OfferStatus.PENDING);

		if (!offerStore.updateStatus(offerID, OfferStatus.PENDING, OfferStatus.ACCEPTED)) {
			throw denied();
		}

	}

	@Transactional
	public String issueSuccessionCreationProof(SuccessionPreview confirmed, String rawPassword, String totpCode) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();

		if (confirmed == null || !principal.getUserID().equals(confirmed.outgoing().userID())) {
			throw denied();
		}

		SuccessionState state = load(confirmed.tenantID(), confirmed.outgoing().userID(),
				confirmed.successor().userID(), Set.copyOf(confirmed.adminRolesToTransfer()));

		if (state.outgoing().getSessionVersion() != principal.getSessionVersion()
				|| !state.preview().equals(confirmed)) {
			throw denied();
		}

		return confirmations.issueProof(Action.SUPER_ADMIN_SUCCESSION_CREATE, state.preview().confirmationDigest(),
				rawPassword, totpCode);

	}

	@Transactional
	public void confirmSuccession(UUID offerID, String rawPassword, String totpCode) {

		if (offerID == null) {
			throw denied();
		}

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();

		SuccessionOffer offer = offerStore.findById(offerID)
				.filter(valid -> valid.status() == OfferStatus.ACCEPTED)
				.filter(valid -> valid.expiresAt().isAfter(clock.instant()))
				.orElseThrow(SuperAdminSuccessionService::denied);

		if (!offer.successorUserID().equals(principal.getUserID())
				&& !offer.outgoingUserID().equals(principal.getUserID())) {
			throw denied();
		}

		SuccessionState state = load(offer.tenantID(), offer.outgoingUserID(),
				offer.successorUserID(), Set.copyOf(offer.adminRolesToTransfer()));

		UserAccount current = principal.getUserID().equals(state.outgoing().getUserID()) ? state.outgoing()
				: state.successor();

		AccountLocks.requireCurrentSession(current, principal);

		if (!offer.previewDigest().equals(state.preview().confirmationDigest())) {
			throw denied();
		}

		SuccessionOffer currentOffer = lockCurrentOffer(offerID, state, OfferStatus.ACCEPTED);

		SuccessionConfirmation confirmation = confirmations.createSuccessionConfirmation(current, offerID,
				currentOffer.previewDigest(), rawPassword, totpCode);

		if (!currentOffer.expiresAt().isAfter(clock.instant())) {
			throw denied();
		}

		confirmationStore.replaceCurrent(confirmation);
	}

	@Transactional
	public void finalizeSuccession(UUID offerID) {

		if (offerID == null) {
			throw denied();
		}

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		SuccessionOffer offer = offerStore.findById(offerID)
				.filter(valid -> valid.status() == OfferStatus.ACCEPTED)
				.filter(valid -> valid.expiresAt().isAfter(clock.instant()))
				.orElseThrow(SuperAdminSuccessionService::denied);

		if (!offer.successorUserID().equals(principal.getUserID())) {
			throw denied();
		}

		SuccessionState state = load(offer.tenantID(), offer.outgoingUserID(),
				offer.successorUserID(), Set.copyOf(offer.adminRolesToTransfer()));

		AccountLocks.requireCurrentSession(state.successor(), principal);

		if (!offer.previewDigest().equals(state.preview().confirmationDigest())) {
			throw denied();
		}

		SuccessionOffer currentOffer = lockCurrentOffer(offerID, state, OfferStatus.ACCEPTED);

		SuccessionConfirmation outgoingConfirmation = confirmationStore
				.findByOfferIDAndUserID(offerID, currentOffer.outgoingUserID())
				.orElseThrow(SuperAdminSuccessionService::denied);
		SuccessionConfirmation successorConfirmation = confirmationStore
				.findByOfferIDAndUserID(offerID, currentOffer.successorUserID())
				.orElseThrow(SuperAdminSuccessionService::denied);

		validateConfirmation(outgoingConfirmation, currentOffer, state.outgoing());
		validateConfirmation(successorConfirmation, currentOffer, state.successor());

		if (!successorConfirmation.sessionIDHash().equals(sessionProvider.getCurrentSessionIDHash())) {
			throw denied();
		}

		Instant authorizedAt = sessionValidityVerifier
				.validateTogether(state.outgoing().getUserID(), state.outgoing().getSessionVersion(),
						outgoingConfirmation.sessionIDHash(), state.successor().getUserID(),
						state.successor().getSessionVersion(), successorConfirmation.sessionIDHash())
				.orElseThrow(SuperAdminSuccessionService::denied);

		if (!currentOffer.expiresAt().isAfter(authorizedAt) || !outgoingConfirmation.expiresAt().isAfter(authorizedAt)
				|| !successorConfirmation.expiresAt().isAfter(authorizedAt)
				|| outgoingConfirmation.confirmedAt().isAfter(authorizedAt)
				|| successorConfirmation.confirmedAt().isAfter(authorizedAt)) {
			throw denied();
		}

		SuccessionPreview confirmed = state.preview();

		UUID oldAdminID = state.outgoing().getUserID();
		UUID newAdminID = state.successor().getUserID();

		if (!tenantStore.replaceSuperAdmin(confirmed.tenantID(), oldAdminID, newAdminID)) {
			throw denied();
		}

		for (UUID structureID : confirmed.adminRolesToTransfer()) {
			Set<UserRole> retainedRoles = new HashSet<>(confirmed.outgoing().rolesIn(structureID));
			retainedRoles.remove(UserRole.ADMIN);
			adminTransfer.moveAdminRole(state.successor().getUserID(), structureID, oldAdminID, retainedRoles,
					newAdminID, confirmed.successor().rolesIn(structureID));
		}

		AuditTarget scope = new AuditTarget(AuditTargetType.TENANT_ORGANIZATION, confirmed.tenantID());

		if (confirmed.outgoingWillBeDisabled()) {
			UUID scopeStructureID = confirmed.adminRolesToTransfer().isEmpty() ? null
					: confirmed.adminRolesToTransfer().getFirst();
			lifecycle.disable(state.outgoing(), scopeStructureID);
		} else {
			lifecycle.invalidateSessions(state.outgoing(), scope);
		}

		lifecycle.invalidateSessions(state.successor(), scope);

		Set<AuditTarget> targets = new HashSet<>();
		targets.add(scope);
		targets.add(new AuditTarget(AuditTargetType.ACCOUNT, oldAdminID));
		targets.add(new AuditTarget(AuditTargetType.ACCOUNT, newAdminID));

		for (UUID structureID : confirmed.adminRolesToTransfer()) {
			targets.add(new AuditTarget(AuditTargetType.ORGANIZATIONAL_STRUCTURE, structureID));
		}

		if (!offerStore.complete(offerID, authorizedAt)) {
			throw denied();
		}

		confirmationStore.deleteByOfferID(offerID);
		auditService.record(AuditAction.SUPER_ADMIN_SUCCESSION, AuditResult.SUCCESS, targets);
	}

	@Transactional
	public SuccessionOffer createSuccessionOffer(SuccessionPreview confirmed, String proof) {

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();

		if (confirmed == null || !principal.getUserID().equals(confirmed.outgoing().userID())) {
			throw denied();
		}

		SuccessionState state = load(confirmed.tenantID(), confirmed.outgoing().userID(),
				confirmed.successor().userID(), Set.copyOf(confirmed.adminRolesToTransfer()));

		if (state.outgoing().getSessionVersion() != principal.getSessionVersion()
				|| !state.preview().equals(confirmed)) {
			throw denied();
		}

		UUID offerID = UUID.randomUUID();
		String previewDigest = state.preview().confirmationDigest();

		tenantStore.findByIdForUpdate(state.preview().tenantID())
				.filter(found -> found.getSuperAdminUserID().equals(state.outgoing().getUserID()))
				.orElseThrow(SuperAdminSuccessionService::denied);

		confirmations.require(state.outgoing(), Action.SUPER_ADMIN_SUCCESSION_CREATE, previewDigest, proof);

		Instant createdAt = clock.instant();
		Instant expiresAt = createdAt.plus(Duration.ofHours(24));

		SuccessionOffer offer = new SuccessionOffer(offerID, state.preview().outgoing().userID(),
				state.preview().successor().userID(),
				state.preview().tenantID(), state.preview().adminRolesToTransfer(), previewDigest, OfferStatus.PENDING,
				createdAt, expiresAt);

		offerStore.create(offer);

		return offer;
	}

	@Transactional
	public void cancelSuccessionOffer(UUID offerID) {

		if (offerID == null) {
			throw denied();
		}

		UserAccount principal = authenticatedUserProvider.getCurrentAccount();
		SuccessionOffer offer = offerStore.findById(offerID).orElseThrow(SuperAdminSuccessionService::denied);

		if (!principal.getUserID().equals(offer.outgoingUserID())) {
			throw denied();
		}

		UserAccount current = accountStore.findByIdForUpdate(offer.outgoingUserID())
				.orElseThrow(SuperAdminSuccessionService::denied);
		AccountLocks.requireCurrentSession(current, principal);
		TenantOrganization organization = tenantStore.findByIdForUpdate(offer.tenantID())
				.orElseThrow(SuperAdminSuccessionService::denied);

		if (!organization.getSuperAdminUserID().equals(principal.getUserID())) {
			throw denied();
		}

		SuccessionOffer currentOffer = offerStore.findByIdForUpdate(offerID)
				.orElseThrow(SuperAdminSuccessionService::denied);

		if (!principal.getUserID().equals(currentOffer.outgoingUserID())
				|| (currentOffer.status() != OfferStatus.PENDING && currentOffer.status() != OfferStatus.ACCEPTED)
				|| !clock.instant().isBefore(currentOffer.expiresAt())
				|| !currentOffer.tenantID().equals(organization.getTenantID())) {
			throw denied();
		}

		if (!offerStore.updateStatus(offerID, currentOffer.status(), OfferStatus.CANCELLED)) {
			throw denied();
		}

		confirmationStore.deleteByOfferID(offerID);

	}

	private SuccessionOffer lockCurrentOffer(UUID offerID, SuccessionState state, OfferStatus expectedStatus) {
		SuccessionPreview preview = state.preview();
		TenantOrganization organization = tenantStore.findByIdForUpdate(preview.tenantID())
				.filter(found -> found.getSuperAdminUserID().equals(preview.outgoing().userID()))
				.orElseThrow(SuperAdminSuccessionService::denied);

		return offerStore.findByIdForUpdate(offerID)
				.filter(offer -> offer.status() == expectedStatus && offer.expiresAt().isAfter(clock.instant()))
				.filter(offer -> offer.tenantID().equals(organization.getTenantID()))
				.filter(offer -> offer.outgoingUserID().equals(organization.getSuperAdminUserID()))
				.filter(offer -> offer.successorUserID().equals(preview.successor().userID()))
				.filter(offer -> offer.previewDigest().equals(preview.confirmationDigest()))
				.orElseThrow(SuperAdminSuccessionService::denied);
	}

	private SuccessionState load(UUID tenantID, UUID outgoingID, UUID successorID, Set<UUID> adminRolesToTransfer) {

		if (tenantID == null || outgoingID == null || successorID == null || outgoingID.equals(successorID)) {
			throw denied();
		}

		List<UUID> structureIDs = structureStore.findIDsByTenant(tenantID).stream().sorted().toList();

		for (UUID structureID : structureIDs) {
			structureStore.findByIdForUpdate(structureID).filter(structure -> tenantID.equals(structure.getTenantID()))
					.orElseThrow(SuperAdminSuccessionService::denied);
		}

		Map<UUID, UserAccount> accounts = adminTransfer.lockAccounts(outgoingID, successorID);
		UserAccount outgoing = accounts.get(outgoingID);
		UserAccount successor = accounts.get(successorID);
		TenantOrganization tenant = tenantStore.findById(tenantID).orElseThrow(SuperAdminSuccessionService::denied);

		if (!tenant.getSuperAdminUserID().equals(outgoingID) || outgoing.getStatu() != UserAccountStatus.ACTIVE
				|| successor.getStatu() != UserAccountStatus.ACTIVE
				|| !authorizationService.hasTenantMembership(outgoingID, tenantID)
				|| !authorizationService.hasTenantMembership(successorID, tenantID)
				|| authorizationService.isDesignatedSuperAdmin(successorID)) {
			throw denied();
		}

		if (!structureIDs.equals(structureStore.findIDsByTenant(tenantID).stream().sorted().toList())
				|| !structureIDs.containsAll(membershipStore.findStructureIDsByUser(outgoingID))
				|| !structureIDs.containsAll(membershipStore.findStructureIDsByUser(successorID))) {
			throw denied();
		}

		PrivilegedAccountSnapshot outgoingSnapshot = adminTransfer.snapshot(outgoing, tenantID);
		PrivilegedAccountSnapshot successorSnapshot = adminTransfer.snapshot(successor, tenantID);

		if (hasInvalidLocalRole(outgoingSnapshot) || hasInvalidLocalRole(successorSnapshot)) {
			throw denied();
		}

		for (UUID structureID : adminRolesToTransfer) {

			if (!structureIDs.contains(structureID)
					|| !outgoingSnapshot.rolesIn(structureID).contains(UserRole.ADMIN)) {
				throw denied();
			}

		}

		boolean disabled = outgoingSnapshot.memberships().stream()
				.noneMatch(membership -> membership.roles().stream().anyMatch(role -> role != UserRole.SUPER_ADMIN
						&& (role != UserRole.ADMIN || !adminRolesToTransfer.contains(membership.structureID()))));

		SuccessionPreview preview = new SuccessionPreview(tenantID, structureIDs, outgoingSnapshot, successorSnapshot,
				adminRolesToTransfer.stream().sorted().toList(), disabled);
		return new SuccessionState(preview, outgoing, successor);
	}

	private void validateConfirmation(SuccessionConfirmation confirmation, SuccessionOffer offer, UserAccount account) {

		Instant now = clock.instant();

		if (confirmation == null
				|| !confirmation.offerID().equals(offer.offerID())
				|| !confirmation.userID().equals(account.getUserID())
				|| confirmation.sessionVersion() != account.getSessionVersion()
				|| !confirmation.previewDigest().equals(offer.previewDigest())
				|| confirmation.confirmedAt().isAfter(now)
				|| !confirmation.expiresAt().isAfter(now)) {
			throw denied();
		}

	}

	private boolean hasInvalidLocalRole(PrivilegedAccountSnapshot snapshot) {
		return snapshot.memberships().stream()
				.anyMatch(membership -> membership.roles().contains(UserRole.SUPER_ADMIN));
	}

	private static AccessDeniedException denied() {
		return new AccessDeniedException(
				"Succession impossible ou état modifié. Une nouvelle confirmation est requise.");
	}

	public record SuccessionPreview(UUID tenantID, List<UUID> structures, PrivilegedAccountSnapshot outgoing,
			PrivilegedAccountSnapshot successor, List<UUID> adminRolesToTransfer, boolean outgoingWillBeDisabled)
			implements AuditTargetSource {

		public SuccessionPreview {
			structures = structures.stream().sorted().toList();
			adminRolesToTransfer = adminRolesToTransfer.stream().sorted().toList();
		}

		@Override
		public Set<AuditTarget> auditTargets() {
			return Set.copyOf(List.of(new AuditTarget(AuditTargetType.TENANT_ORGANIZATION, tenantID),
					new AuditTarget(AuditTargetType.ACCOUNT, outgoing.userID()),
					new AuditTarget(AuditTargetType.ACCOUNT, successor.userID())));
		}

		public String confirmationDigest() {
			return PrivilegedConfirmationService.digest(this);
		}
	}

	private record SuccessionState(SuccessionPreview preview, UserAccount outgoing, UserAccount successor) {
	}
}
