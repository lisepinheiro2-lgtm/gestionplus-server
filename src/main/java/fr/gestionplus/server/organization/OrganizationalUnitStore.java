package fr.gestionplus.server.organization;

import java.util.Optional;
import java.util.UUID;

public interface OrganizationalUnitStore {

	void createUnit(OrganizationalUnit unit);

	Optional<OrganizationalUnit> findById(UUID unitId);
}
