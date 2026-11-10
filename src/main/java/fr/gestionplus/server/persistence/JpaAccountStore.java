package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Repository
public class JpaAccountStore implements AccountStore {

	private final UserAccountJpaRepository accountJpaRepository;
	private final EmployeeJpaRepository employeeJpaRepository;

	@PersistenceContext
	private EntityManager entityManager;

	public JpaAccountStore(UserAccountJpaRepository accountJpaRepository, EmployeeJpaRepository employeeJpaRepository) {
		this.accountJpaRepository = Objects.requireNonNull(accountJpaRepository);
		this.employeeJpaRepository = Objects.requireNonNull(employeeJpaRepository);
	}

	@Override
	@Transactional
	public void createAccount(UserAccount account) {
		Objects.requireNonNull(account);
		UUID employeeID = account.getEmployeeID();

		if (employeeID == null && account.getStatu() != UserAccountStatus.PENDING_ACTIVATION)
			throw new IllegalArgumentException("Un nouveau compte doit être rattaché à un salarié.");

		if (employeeID != null && !employeeJpaRepository.existsById(employeeID))
			throw new IllegalArgumentException("Le salarié associé au compte n'existe pas.");

		entityManager.persist(new UserAccountEntity(account));
	}

	@Override
	public void disableAccount(UUID userID) {
		UserAccountEntity entity = accountJpaRepository.findById(userID).orElseThrow();
		entity.disable();
		accountJpaRepository.save(entity);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UserAccount> findByEmployeeID(UUID employeeID) {
		return accountJpaRepository.findByEmployeeID(employeeID).map(account -> account.toDomain());
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UserAccount> findById(UUID userID) {

		Optional<UserAccountEntity> entity = accountJpaRepository.findById(userID);
		return entity.map(account -> account.toDomain());
	}
}
