package fr.gestionplus.server.account;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "gestionplus.sms.fictif", havingValue = "true")
public class FakeActivationMessageSender implements ActivationMessageSender {

	private static final Logger LOGGER = LoggerFactory.getLogger(FakeActivationMessageSender.class);

	@Override
	public void send(String phoneNumber, String code) {
		LOGGER.info("Envoi du SMS fictif {} ({})", phoneNumber, code);
	}

}
