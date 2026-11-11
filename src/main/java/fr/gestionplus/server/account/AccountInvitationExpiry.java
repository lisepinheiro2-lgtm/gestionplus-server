package fr.gestionplus.server.account;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "gestionplus.activation.expiry-enabled", havingValue = "true", matchIfMissing = true)
class AccountInvitationExpiry {

	private static final Logger LOGGER = LoggerFactory.getLogger(AccountInvitationExpiry.class);

	private final AccountInvitationStore store;
	private final AccountInvitationService service;

	AccountInvitationExpiry(AccountInvitationStore store, AccountInvitationService service) {
		this.store = store;
		this.service = service;
	}

	@Scheduled(fixedDelay = 60000, initialDelay = 60000)
	public void expireDueInvitations() {

		for (UUID invitationID : store.findExpired(Instant.now())) {

			try {
				service.expireInvitation(invitationID);
			} catch (RuntimeException failure) {
				LOGGER.warn("Échec de l’expiration de l’invitation {} ({})", invitationID,
						failure.getClass().getSimpleName());
			}

		}

	}
}
