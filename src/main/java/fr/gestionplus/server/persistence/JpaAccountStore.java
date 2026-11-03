package fr.gestionplus.server.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.auth.UserAccount;

@Repository
public class JpaAccountStore implements AccountStore {

	private final UserAccountJpaRepository accountJpaRepository;
	private final EmployeeJpaRepository employeeJpaRepository;

	public JpaAccountStore(UserAccountJpaRepository accountJpaRepository, EmployeeJpaRepository employeeJpaRepository) {
		this.accountJpaRepository = Objects.requireNonNull(accountJpaRepository);
		this.employeeJpaRepository = Objects.requireNonNull(employeeJpaRepository);
	}

	@Override
	public void createAccount(UserAccount account) {
		Objects.requireNonNull(account);

		if (account.getEmployeeID() == null)
			throw new IllegalArgumentException("Un nouveau compte doit être rattaché à un salarié.");

		if (!employeeJpaRepository.existsById(account.getEmployeeID()))
			throw new IllegalArgumentException("Le salarié associé au compte n'existe pas.");

		UserAccountEntity accountEntity = new UserAccountEntity(account);
		accountJpaRepository.save(accountEntity);
	}

	@Override
	public void disableAccount(UUID userID) {
		UserAccountEntity entity = accountJpaRepository.findById(userID).orElseThrow();
		entity.disable();
		accountJpaRepository.save(entity);
	}

	@Override
	@Transactional(readOnly = true)
	public Optional<UserAccount> findById(UUID userID) {

		Optional<UserAccountEntity> entity = accountJpaRepository.findById(userID);
		return entity.map(account -> account.toDomain());
	}
}
