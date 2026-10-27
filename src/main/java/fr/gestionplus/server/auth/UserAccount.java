package fr.gestionplus.server.auth;

import java.util.Set;
import java.util.UUID;
import java.util.Objects;

public class UserAccount {

	private final UUID userID;
	private final UUID employeeID;
	private String userName;
	private UserAccountStatus statu;
	private final Set<UserRole> roles;

	public UserAccount(UUID userID, String userName, UserAccountStatus statu, Set<UserRole> roles, UUID employeeID) {
		this.userID = Objects.requireNonNull(userID, "L'UUID de l'utilisateur ne peut pas être null");
		this.employeeID = employeeID;
		
		if (userName != null) {
			this.userName = userName.strip();
		}
		
		this.statu = Objects.requireNonNull(statu);

		if (userName == null && statu == UserAccountStatus.ACTIVE)
			throw new IllegalArgumentException("Le nom d'utilisateur ne peut pas être null");

		if (userName != null && this.userName.isBlank())
			throw new IllegalArgumentException("Le nom d'utilisateur ne peut pas être vide");

		Objects.requireNonNull(roles, "Les rôles ne peuvent pas être null");
		
		if (roles.isEmpty()) {
			throw new IllegalArgumentException("Un compte doit posséder au moins un rôle");
		}
		
		this.roles = Set.copyOf(roles);
	}

	public UUID getUserID() {
		return userID;
	}

	public UUID getEmployeeID() {
		return employeeID;
	}

	public Set<UserRole> getRoles() {
		return roles;
	}

	public String getUserName() {
		return userName;
	}

	public UserAccountStatus getStatu() {
		return statu;
	}
}
