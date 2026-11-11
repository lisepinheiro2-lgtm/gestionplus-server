package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.UUID;

public class OrganizationalStructure {

	private final UUID structureID;
	private final String structureName;
	private final UUID tenantID;

	public OrganizationalStructure(UUID structureID, String structureName, UUID tenantID) {
		this.structureID = Objects.requireNonNull(structureID);
		this.structureName = Objects.requireNonNull(structureName).strip();
		this.tenantID = Objects.requireNonNull(tenantID);

		if (structureName.isBlank()) {
			throw new IllegalArgumentException("Le nom de la structure ne peut pas être vide");
		}

	}

	public UUID getStructureID() {
		return structureID;
	}

	public String getStructureName() {
		return structureName;
	}

	public UUID getTenantID() {
		return tenantID;
	}
}
