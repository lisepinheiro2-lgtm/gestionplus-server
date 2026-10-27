package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.UUID;

public class OrganizationalUnit {

	private final UUID unitId;
	private String name;

	public OrganizationalUnit(UUID unitId, String name) {
		this.unitId = Objects.requireNonNull(unitId);

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
}
