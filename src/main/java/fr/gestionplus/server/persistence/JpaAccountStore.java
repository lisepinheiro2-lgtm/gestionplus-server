package fr.gestionplus.server.persistence;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import fr.gestionplus.server.account.AccountInvitationStore;
import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserCredential;
import fr.gestionplus.server.auth.UserRole;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

@Repository
public class JpaAccountStore implements AccountStore {

	private final UserAccountJpaRepository accountJpaRepository;
	private final EmployeeJpaRepository employeeJpaRepository;
	private final StructureMembershipJpaRepository membershipRepository;
	private final UserCredentialJpaRepository credentialRepository;
	private final AccountInvitationStore invitationStore;
	private final TenantOrganizationJpaRepository tenantRepository;

	@PersistenceContext
	private EntityManager entityManager;

	public JpaAccountStore(UserAccountJpaRepository accountJpaRepository, EmployeeJpaRepository employeeJpaRepository,
			StructureMembershipJpaRepository membershipRepository, UserCredentialJpaRepository credentialRepository,
			AccountInvitationStore invitationStore, TenantOrganizationJpaRepository tenantRepository) {
		this.accountJpaRepository = Objects.requireNonNull(accountJpaRepository);
		this.employeeJpaRepository = Objects.requireNonNull(employeeJpaRepository);
		this.membershipRepository = Objects.requireNonNull(membershipRepository);
		this.credentialRepository = Objects.requireNonNull(credentialRepository);
		this.invitationStore = Objects.requireNonNull(invitationStore);
		this.tenantRepository = Objects.requireNonNull(tenantRepository);
	}

	@Override
	@Transactional
	public void createAccount(UserAccount account) {
		Objects.requireNonNull(account);
		UUID employeeID = account.getEmployeeID();

		if (employeeID == null && account.getStatu() != UserAccountStatus.PENDING_ACTIVATION) {
			throw new IllegalArgumentException("Un nouveau compte doit être rattaché à un salarié.");
		}

		if (employeeID != null && !employeeJpaRepository.existsById(employeeID)) {
			throw new IllegalArgumentException("Le salarié associé au compte n'existe pas.");
		}

		entityManager.persist(new UserAccountEntity(account));
	}

	@Override
	@Transactional
	public void disableAccount(UUID userID) {
		UserAccountEntity entity = accountJpaRepository.findByIdForUpdate(userID).orElseThrow();
		List<StructureMembershipEntity> memberships = membershipRepository.findByUserID(userID);

		if (tenantRepository.existsBySuperAdminUserID(userID) || memberships.stream()
				.map(StructureMembershipEntity::toDomain)
				.anyMatch(membership -> membership.getRoles().contains(UserRole.ADMIN))) {
			throw new IllegalStateException("Le compte détient une fonction d’administration protégée.");
		}

		invitationStore.removeForAccount(userID);
		membershipRepository.deleteAll(memberships);
		entity.disable();
		membershipRepository.flush();
	}

	@Override
	@Transactional
	public void advanceSessionVersion(UUID userID) {
		accountJpaRepository.findByIdForUpdate(userID).orElseThrow().advanceSessionVersion();
	}

	@Override
	@Transactional
	public void setPendingActivation(UUID userID) {
		accountJpaRepository.findByIdForUpdate(userID).orElseThrow().setPendingActivation();
	}

	@Override
	@Transactional
	public void updateEmail(UUID userID, String email) {
		accountJpaRepository.findByIdForUpdate(userID).orElseThrow().updateEmail(email);
	}

	@Override
	@Transactional
	public void activateAccount(UUID userID, String userName, UserCredential credential) {

		if (!userID.equals(credential.getUserId())) {
			throw new IllegalArgumentException("L’identifiant du secret ne correspond pas au compte.");
		}

		UserAccountEntity entity = accountJpaRepository.findByIdForUpdate(userID).orElseThrow();
		entity.activate(userName);
		credentialRepository.save(new UserCredentialEntity(credential));
	}

	@Override
	@Transactional
	public void replaceCredential(UserCredential credential) {
		UserAccountEntity entity = accountJpaRepository.findByIdForUpdate(credential.getUserId()).orElseThrow();

		if (entity.toDomain().getStatu() != UserAccountStatus.ACTIVE) {
			throw new IllegalStateException("Le compte doit être actif pour changer son mot de passe.");
		}

		credentialRepository.save(new UserCredentialEntity(credential));
	}

	@Override
	public Optional<UserAccount> findByIdForUpdate(UUID userID) {
		return accountJpaRepository.findByIdForUpdate(userID).map(UserAccountEntity::toDomain);
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
