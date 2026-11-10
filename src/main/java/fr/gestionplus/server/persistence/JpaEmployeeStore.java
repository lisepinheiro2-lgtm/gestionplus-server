package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import fr.gestionplus.server.employee.Employee;
import fr.gestionplus.server.employee.EmployeeStore;

@Repository
public class JpaEmployeeStore implements EmployeeStore {

	private final EmployeeJpaRepository repository;

	public JpaEmployeeStore(EmployeeJpaRepository repository) {
		this.repository = Objects.requireNonNull(repository);
	}

	@Override
	public void createEmployee(Employee employee) {
		Objects.requireNonNull(employee);

		if (employee.getUnitId() != null && employee.getStructureID() == null)
			throw new IllegalArgumentException("Le secteur et le salarié doivent appartenir à la même structure.");

		EmployeeEntity entity = new EmployeeEntity(employee);
		repository.save(entity);
	}

	@Override
	public Optional<Employee> findById(UUID employeeID) {
		return repository.findById(employeeID).map(entity -> entity.toEmployee());
	}

	@Override
	public Optional<Employee> findByIdForUpdate(UUID employeeID) {
		return repository.findByIdForUpdate(employeeID).map(entity -> entity.toEmployee());
	}
}
