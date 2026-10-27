package fr.gestionplus.server.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.gestionplus.server.employee.Employee;

class EmployeeEntityTest {

	@Test
	void preservesEmployeeWithUnitDuringConversion() {
		UUID employeeID = UUID.fromString("00000000-0000-0000-0000-000000000001");
		UUID unitId = UUID.fromString("00000000-0000-0000-0000-000000000002");
		Employee employee = new Employee(employeeID, "Camille", "Exemple", 28.5,
				"camille@example.invalid", "0600000000", unitId);

		Employee restored = new EmployeeEntity(employee).toEmployee();

		assertEquals(employeeID, restored.getEmployeeID());
		assertEquals(unitId, restored.getUnitId());
		assertNotEquals(restored.getEmployeeID(), restored.getUnitId());
		assertEquals(employee.getFirstName(), restored.getFirstName());
		assertEquals(employee.getLastName(), restored.getLastName());
		assertEquals(employee.getContractHours(), restored.getContractHours());
		assertEquals(employee.getEmail(), restored.getEmail());
		assertEquals(employee.getPhoneNumber(), restored.getPhoneNumber());
	}

	@Test
	void preservesEmployeeWithoutUnitOrContactsDuringConversion() {
		UUID employeeID = UUID.randomUUID();
		Employee employee = new Employee(employeeID, "Alex", "Exemple", 0, null, null, null);

		Employee restored = new EmployeeEntity(employee).toEmployee();

		assertEquals(employeeID, restored.getEmployeeID());
		assertEquals(employee.getFirstName(), restored.getFirstName());
		assertEquals(employee.getLastName(), restored.getLastName());
		assertEquals(employee.getContractHours(), restored.getContractHours());
		assertNull(restored.getEmail());
		assertNull(restored.getPhoneNumber());
		assertNull(restored.getUnitId());
	}
}
