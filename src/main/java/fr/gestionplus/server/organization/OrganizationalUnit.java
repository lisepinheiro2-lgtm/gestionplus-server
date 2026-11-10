package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.UUID;

public class OrganizationalUnit {

	private final UUID unitId;
	private String name;
	private final UUID structureID;

	public OrganizationalUnit(UUID unitId, String name) {
		this(unitId, name, null);
	}

	public OrganizationalUnit(UUID unitId, String name, UUID structureID) {
		this.unitId = Objects.requireNonNull(unitId);
		this.structureID = structureID;

		if (name == null || name.isBlank())
			throw new IllegalArgumentException("Le nom du secteur est obligatoire.");

		this.name = name.strip();
	}

	public UUID getUnitId() {
		return unitId;
	}

	public String getName() {
		return name;
	}

	public UUID getStructureID() {
		return structureID;
	}
}
