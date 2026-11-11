package fr.gestionplus.server.auth;

import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("prod")
@Lazy(false)
final class MfaProductionConfiguration {

	MfaProductionConfiguration(MfaSecretCipher cipher) {

		if (!cipher.available()) {
			throw new IllegalStateException("La clé de chiffrement MFA est obligatoire en production.");
		}

	}
}
