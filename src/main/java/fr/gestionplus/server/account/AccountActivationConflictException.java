package fr.gestionplus.server.account;

public class AccountActivationConflictException extends RuntimeException {

	public AccountActivationConflictException() {
		super("Activation impossible en raison d’un conflit.");
	}
}
