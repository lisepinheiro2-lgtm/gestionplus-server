package fr.gestionplus.server.api;

public final class AccountActivationRoutes {

	public static final String SMS_CHALLENGE_PATH = "/api/invitations/{invitationID}/sms-challenge";
	public static final String ACTIVATION_PATH = "/api/invitations/{invitationID}/activation";

	private AccountActivationRoutes() {
	}
}
