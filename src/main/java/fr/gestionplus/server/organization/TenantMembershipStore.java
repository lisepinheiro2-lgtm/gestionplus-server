package fr.gestionplus.server.organization;

import java.util.Optional;
import java.util.UUID;

public interface TenantMembershipStore {

	Optional<TenantMembership> findByUserAndTenant(UUID userID, UUID tenantID);

	void save(TenantMembership tenantMembership);
}
