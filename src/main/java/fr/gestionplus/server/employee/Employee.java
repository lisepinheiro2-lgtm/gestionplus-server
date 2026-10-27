package fr.gestionplus.server.employee;

import java.util.Objects;
import java.util.UUID;

public class Employee {

	private final UUID employeeID;
	private String firstName;
	private String lastName;
	private double contractHours;
	private String email;
	private String phoneNumber;
	private UUID unitId;

	public Employee(UUID employeeID, String firstName, String lastName, double contractHours, String email,
			String phoneNumber, UUID unitId) {

		this.employeeID = Objects.requireNonNull(employeeID);

		if (firstName == null || firstName.isBlank())
			throw new IllegalArgumentException("Le prénom est obligatoire.");

		this.firstName = firstName.strip();

		if (lastName == null || lastName.isBlank())
			throw new IllegalArgumentException("Le nom est obligatoire.");

		this.lastName = lastName.strip();

		if (contractHours < 0 || !Double.isFinite(contractHours))
			throw new IllegalArgumentException(
					"Les heures contractuelles doivent être un nombre fini et positif ou nul.");

		this.contractHours = contractHours;

		this.email = email;
		this.phoneNumber = phoneNumber;
		this.unitId = unitId;
	}

	public UUID getEmployeeID() {
		return employeeID;
	}

	public String getFirstName() {
		return firstName;
	}

	public String getLastName() {
		return lastName;
	}

	public double getContractHours() {
		return contractHours;
	}

	public String getEmail() {
		return email;
	}

	public String getPhoneNumber() {
		return phoneNumber;
	}

	public UUID getUnitId() {
		return unitId;
	}
}
