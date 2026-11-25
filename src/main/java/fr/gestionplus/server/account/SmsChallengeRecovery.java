package fr.gestionplus.server.account;

import java.time.Clock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
@ConditionalOnProperty(name = "gestionplus.activation.challenge-recovery.enabled", havingValue = "true", matchIfMissing = true)
public final class SmsChallengeRecovery {

	private static final Logger LOGGER = LoggerFactory.getLogger(SmsChallengeRecovery.class);
	private final SmsIdentityChallengeStore store;
	private final Clock clock;

	public SmsChallengeRecovery(SmsIdentityChallengeStore store, ObjectProvider<Clock> clocks) {
		this.store = store;
		clock = clocks.getIfAvailable(Clock::systemUTC);
	}

	@Scheduled(fixedDelay = 30000, initialDelay = 30000)
	@EventListener(ApplicationReadyEvent.class)
	public void recoverUnconfirmedSends() {

		try {
			store.revokeUnconfirmedSends(clock.instant());
		} catch (RuntimeException failure) {
			LOGGER.warn("Récupération des envois SMS indisponible ({}).", failure.getClass().getSimpleName());
		}

	}
}
