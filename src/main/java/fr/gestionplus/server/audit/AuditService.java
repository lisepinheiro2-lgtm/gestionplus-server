package fr.gestionplus.server.audit;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.dao.DataAccessException;

import fr.gestionplus.server.organization.OrganizationalStructureStore;
import fr.gestionplus.server.organization.StructureMembership;
import fr.gestionplus.server.organization.StructureMembershipStore;
import fr.gestionplus.server.organization.TenantOrganizationStore;

public class AuditService {

	private final Consumer<AuditEvent> writer;
	private final OrganizationalStructureStore structures;
	private final TenantOrganizationStore tenants;
	private final AuditContextProvider contextProvider;
	private final StructureMembershipStore memberships;

	public AuditService(AuditStore auditStore, AuditContextProvider contextProvider) {
		this(Objects.requireNonNull(auditStore)::save, contextProvider, null, null, null);
	}

	AuditService(Consumer<AuditEvent> writer, AuditContextProvider contextProvider,
			OrganizationalStructureStore structures, TenantOrganizationStore tenants,
			StructureMembershipStore memberships) {
		this.writer = writer;
		this.contextProvider = Objects.requireNonNull(contextProvider);
		this.structures = structures;
		this.tenants = tenants;
		this.memberships = memberships;
	}

	public void record(AuditAction action, AuditResult result, Set<AuditTarget> targets) {
		record(action, result, targets, AuditMetadata.empty());
	}

	public void record(AuditAction action, AuditResult result, Set<AuditTarget> targets, AuditMetadata metadata) {

		recordForActor(action, result, contextProvider.getActorUserId(), contextProvider.getIpAddress(), targets,
				metadata);
	}

	public void recordForActor(AuditAction action, AuditResult result, UUID actorUserId, String ipAddress,
			Set<AuditTarget> targets, AuditMetadata metadata) {

		UUID eventId = UUID.randomUUID();
		Instant occurredAt = Instant.now();
		UUID tenantId = resolveTenant(targets, result);

		writer.accept(new AuditEvent(occurredAt, eventId, actorUserId, action, result, ipAddress, targets, metadata,
				tenantId));
	}

	private UUID resolveTenant(Set<AuditTarget> targets, AuditResult result) {
		Set<UUID> tenantIDs = new HashSet<>();

		for (AuditTarget target : targets) {

			try {

				if (target.getTargetType() == AuditTargetType.TENANT_ORGANIZATION && tenants != null) {
					tenants.findById(target.getTargetId()).ifPresent(tenant -> tenantIDs.add(tenant.getTenantID()));
				}

				if (target.getTargetType() == AuditTargetType.ORGANIZATIONAL_STRUCTURE && structures != null) {
					structures.findById(target.getTargetId())
							.ifPresent(structure -> tenantIDs.add(structure.getTenantID()));
				}

				if (target.getTargetType() == AuditTargetType.ACCOUNT && memberships != null) {
					resolveAccountTenant(target.getTargetId(), tenantIDs);
				}

			} catch (DataAccessException failure) {

				if (result == AuditResult.SUCCESS) {
					throw failure;
				}

			}

		}

		return tenantIDs.size() == 1 ? tenantIDs.iterator().next() : null;
	}

	private void resolveAccountTenant(UUID accountID, Set<UUID> tenantIDs) {

		if (tenants != null) {
			tenants.findBySuperAdminUserID(accountID).ifPresent(tenant -> tenantIDs.add(tenant.getTenantID()));
		}

		if (structures != null) {

			for (StructureMembership membership : memberships.findByUser(accountID)) {
				structures.findById(membership.getStructureID())
						.ifPresent(structure -> tenantIDs.add(structure.getTenantID()));
			}

		}

	}
}
