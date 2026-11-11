package fr.gestionplus.server.persistence;

import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import fr.gestionplus.server.employee.EmployeeResponsibilityStore;

@Repository
public class PostgresqlEmployeeResponsibilityStore implements EmployeeResponsibilityStore {

	private final JdbcTemplate jdbcTemplate;

	public PostgresqlEmployeeResponsibilityStore(JdbcTemplate jdbcTemplate) {
		this.jdbcTemplate = jdbcTemplate;
	}

	@Override
	public boolean isResponsibleFor(UUID managerUserID, UUID employeeID, UUID structureID) {
		return Boolean.TRUE.equals(jdbcTemplate.queryForObject("""
				SELECT EXISTS (
				    SELECT 1 FROM employee_responsibilities
				    WHERE employee_id = ? AND structure_id = ? AND manager_user_id = ?
				)
				""", Boolean.class, employeeID, structureID, managerUserID));
	}

	@Override
	public void assignManager(UUID employeeID, UUID structureID, UUID managerUserID) {
		jdbcTemplate.update("""
				INSERT INTO employee_responsibilities (employee_id, structure_id, manager_user_id)
				VALUES (?, ?, ?)
				ON CONFLICT (employee_id) DO UPDATE
				SET structure_id = EXCLUDED.structure_id, manager_user_id = EXCLUDED.manager_user_id
				""", employeeID, structureID, managerUserID);
	}
}
