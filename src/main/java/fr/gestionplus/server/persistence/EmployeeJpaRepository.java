package fr.gestionplus.server.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EmployeeJpaRepository extends JpaRepository<EmployeeEntity, UUID> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("SELECT employee FROM EmployeeEntity employee WHERE employee.employeeID = :employeeID")
	Optional<EmployeeEntity> findByIdForUpdate(@Param("employeeID") UUID employeeID);
}
