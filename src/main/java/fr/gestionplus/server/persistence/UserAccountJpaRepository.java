package fr.gestionplus.server.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserAccountJpaRepository extends JpaRepository<UserAccountEntity, UUID> {

	@EntityGraph(attributePaths = "roles")
	Optional<UserAccountEntity> findByUserName(String userName);

	@EntityGraph(attributePaths = "roles")
	@Query("SELECT account FROM UserAccountEntity account WHERE account.employeeID = :employeeID")
	Optional<UserAccountEntity> findByEmployeeID(@Param("employeeID") UUID employeeID);
}
