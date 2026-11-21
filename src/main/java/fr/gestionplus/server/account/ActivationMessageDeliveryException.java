package fr.gestionplus.server.account;

public class ActivationMessageDeliveryException extends RuntimeException {

	public ActivationMessageDeliveryException() {
		super("L’envoi du code d’activation est temporairement indisponible.");
	}
}
