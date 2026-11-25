package fr.gestionplus.server.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import fr.gestionplus.server.account.SmsIdentityChallenge.Purpose;

@Service
@ConditionalOnProperty(name = "gestionplus.sms.fictif", havingValue = "true")
public class FakeActivationMessageSender implements SmsMessageSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(FakeActivationMessageSender.class);

	@Override
	public void send(Purpose purpose, String phoneNumber, String code) {
		if (purpose == Purpose.ACCOUNT_ACTIVATION) {
			LOGGER.info("Envoi du SMS fictif {} ({})", phoneNumber, code);
		} else {
			LOGGER.info("Envoi du SMS fictif MFA.");
		}
	}

}
