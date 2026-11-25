package fr.gestionplus.server.account;

import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;

public class ActivationMessageDeliveryException extends RuntimeException {

	public ActivationMessageDeliveryException() {
		this(Purpose.ACCOUNT_ACTIVATION);
	}

	public ActivationMessageDeliveryException(Purpose purpose) {
		super(purpose == Purpose.ACCOUNT_ACTIVATION
				? "L’envoi du code d’activation est temporairement indisponible."
				: "L’envoi du code de configuration MFA est temporairement indisponible.");
	}
}
