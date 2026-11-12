package fr.gestionplus.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.stereotype.Component;

@Component
public class LoginAttemptLimiter {

	private static final Duration WINDOW = Duration.ofMinutes(15);
	private static final Duration FIRST_RESTRICTION = Duration.ofMinutes(5);
	private static final int FAILURES_PER_ADDRESS_WEIGHT = 10;
	private static final int LIMIT = 10;
	private static final int MAX_SCOPES = 8192;
	private static final int MAX_ADDRESS_FAILURES = 30;
	private static final long ACCOUNT_DELAY_STEP_MS = 200L;
	private static final long ADDRESS_DELAY_STEP_MS = 500L;
	private static final long MAX_DELAY_MS = 2000L;

	private final Clock clock;
	private final SecretKeySpec key;
	private final Map<String, History> accounts = new LinkedHashMap<>();
	private final Map<String, History> addresses = new HashMap<>();

	public LoginAttemptLimiter() {
		this(Clock.systemUTC());
	}

	LoginAttemptLimiter(Clock clock) {
		this.clock = clock;
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		key = new SecretKeySpec(bytes, "HmacSHA256");
	}

	synchronized Attempt begin(String userName, String address) {
		Instant now = clock.instant();
		String accountKey = digest("account", userName);
		String addressKey = digest("address", address);
		History account = accountHistory(accountKey, now);
		History source = addresses.get(addressKey);

		if (account == null || account.inFlight
				|| now.isBefore(account.blockedUntil)) {
			return null;
		}

		Instant lastFailure = null;

		if (!account.failures.isEmpty()) {
			lastFailure = account.failures.getLast();
		}

		if (source != null && !source.failures.isEmpty()
				&& (lastFailure == null || source.failures.getLast().isAfter(lastFailure))) {
			lastFailure = source.failures.getLast();
		}

		if (lastFailure != null) {
			int addressWeight = source == null ? 0 : source.failures.size() / FAILURES_PER_ADDRESS_WEIGHT;
			int accountWeight = account.failures.size();
			long delayMs = Math.min(MAX_DELAY_MS,
					ACCOUNT_DELAY_STEP_MS * accountWeight + ADDRESS_DELAY_STEP_MS * addressWeight);

			if (now.isBefore(lastFailure.plusMillis(delayMs))) {
				return null;
			}

		}

		account.inFlight = true;
		return new Attempt(accountKey, addressKey);
	}

	synchronized boolean finish(Attempt attempt, boolean failed, boolean knownAccount) {
		History account = accounts.get(attempt.accountKey());
		account.inFlight = false;
		Instant now = clock.instant();
		account.expire(now);

		if (!failed) {

			if (account.failures.isEmpty()) {
				accounts.remove(attempt.accountKey());
			}

			return false;
		}

		account.knownAccount |= knownAccount;
		History address = getOrCreate(addresses, attempt.addressKey(), now);

		if (address != null) {
			address.add(now, MAX_ADDRESS_FAILURES);
		}

		boolean restrictionStarted = account.failures.size() >= LIMIT - 1;
		account.add(now, LIMIT);

		if (restrictionStarted) {
			account.blockedUntil = now.plus(FIRST_RESTRICTION);
		}

		return restrictionStarted;
	}

	private History accountHistory(String key, Instant now) {
		History account = getOrCreate(accounts, key, now);

		if (account != null) {
			return account;
		}

		Iterator<History> histories = accounts.values().iterator();

		while (histories.hasNext()) {
			History history = histories.next();

			if (!history.knownAccount && !history.inFlight) {
				histories.remove();
				return getOrCreate(accounts, key, now);
			}

		}

		return null;
	}

	private History getOrCreate(Map<String, History> histories, String key, Instant now) {
		History history = histories.get(key);

		if (history != null) {
			history.expire(now);
			return history;
		}

		if (histories.size() >= MAX_SCOPES) {
			histories.values().removeIf(value -> value.expire(now));
		}

		if (histories.size() >= MAX_SCOPES) {
			return null;
		}

		History created = new History();
		histories.put(key, created);
		return created;
	}

	private String digest(String scope, String value) {

		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(key);
			return HexFormat.of().formatHex(mac.doFinal((scope + ":" + (value == null ? "" : value))
					.getBytes(StandardCharsets.UTF_8)));
		} catch (GeneralSecurityException failure) {
			throw new IllegalStateException("Protection des tentatives de connexion indisponible.", failure);
		}

	}

	record Attempt(String accountKey, String addressKey) {
	}

	private static final class History {

		private final Deque<Instant> failures = new ArrayDeque<>();
		private Instant blockedUntil = Instant.MIN;
		private boolean inFlight;
		private boolean knownAccount;

		boolean expire(Instant now) {
			Instant cutoff = now.minus(WINDOW);

			while (!failures.isEmpty() && !failures.getFirst().isAfter(cutoff)) {
				failures.removeFirst();
			}

			return !inFlight && failures.isEmpty() && !now.isBefore(blockedUntil);
		}

		void add(Instant now, int maximum) {

			if (failures.size() >= maximum) {
				failures.removeFirst();
			}

			failures.addLast(now);
		}
	}
}
