package fr.gestionplus.server.employee;

import java.util.UUID;

public interface EmployeeResponsibilityStore {

	boolean isResponsibleFor(UUID managerUserID, UUID employeeID, UUID structureID);

	void assignManager(UUID employeeID, UUID structureID, UUID managerUserID);
}
