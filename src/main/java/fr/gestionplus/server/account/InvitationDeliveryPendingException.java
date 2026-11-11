package fr.gestionplus.server.account;

public class InvitationDeliveryPendingException extends RuntimeException {

	public InvitationDeliveryPendingException() {
		super("L’activation est temporairement indisponible.");
	}
}
