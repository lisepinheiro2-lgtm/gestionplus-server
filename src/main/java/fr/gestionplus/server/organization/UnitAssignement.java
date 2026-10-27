package fr.gestionplus.server.organization;

import java.util.Objects;
import java.util.UUID;

public class UnitAssignement {

	private final UUID unitID;
	private final UUID employeeId;

	public UnitAssignement(UUID unitId, UUID employeeId) {
		this.unitID = Objects.requireNonNull(unitId);
		this.employeeId = Objects.requireNonNull(employeeId);
	}

	public UUID getUnitID() {
		return unitID;
	}

	public UUID getEmployeeId() {
		return employeeId;
	}
}
