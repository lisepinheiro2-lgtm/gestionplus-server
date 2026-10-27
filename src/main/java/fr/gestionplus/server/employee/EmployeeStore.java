package fr.gestionplus.server.employee;

import java.util.Optional;
import java.util.UUID;

public interface EmployeeStore {

	void createEmployee(Employee employee);

	Optional<Employee> findById(UUID employeeID);
}
