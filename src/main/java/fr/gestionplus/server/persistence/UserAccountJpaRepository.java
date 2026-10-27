package fr.gestionplus.server.persistence;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserAccountJpaRepository extends JpaRepository<UserAccountEntity, UUID> {

	@EntityGraph(attributePaths = "roles")
	Optional<UserAccountEntity> findByUserName(String userName);
}
