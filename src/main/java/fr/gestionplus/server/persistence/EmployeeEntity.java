package fr.gestionplus.server.persistence;

import java.util.UUID;
import fr.gestionplus.server.employee.Employee;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "employees")
public class EmployeeEntity {

	@Id
	@Column(name = "employee_id", nullable = false)
	private UUID employeeID;

	@Column(name = "first_name", nullable = false)
	private String firstName;

	@Column(name = "last_name", nullable = false)
	private String lastName;

	@Column(name = "contract_hours", nullable = false)
	private double contractHours;

	@Column(name = "email")
	private String email;

	@Column(name = "phone_number")
	private String phoneNumber;

	@Column(name = "unit_id")
	private UUID unitId;

	protected EmployeeEntity() {
	}

	public EmployeeEntity(Employee employee) {
		this.employeeID = employee.getEmployeeID();
		this.firstName = employee.getFirstName();
		this.lastName = employee.getLastName();
		this.contractHours = employee.getContractHours();
		this.email = employee.getEmail();
		this.phoneNumber = employee.getPhoneNumber();
		this.unitId = employee.getUnitId();
	}

	public Employee toEmployee() {
		return new Employee(employeeID, firstName, lastName, contractHours, email, phoneNumber, unitId);
	}
}
