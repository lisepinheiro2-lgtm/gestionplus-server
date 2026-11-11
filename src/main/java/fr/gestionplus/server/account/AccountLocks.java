package fr.gestionplus.server.account;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Supplier;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;

final class AccountLocks {

	private AccountLocks() {
	}

	static void requireCurrentSession(UserAccount current, UserAccount principal) {

		if (current.getStatu() != UserAccountStatus.ACTIVE
				|| current.getSessionVersion() != principal.getSessionVersion()) {
			throw new AuditedAccessDeniedException("Une nouvelle connexion est requise.", AuditReason.STALE_SESSION);
		}

	}

	static Map<UUID, UserAccount> lock(AccountStore store, Supplier<? extends RuntimeException> denied,
			UUID... userIDs) {

		Map<UUID, UserAccount> accounts = new HashMap<>();

		for (UUID userID : new TreeSet<>(List.of(userIDs))) {
			UserAccount account = store.findByIdForUpdate(userID).filter(current -> userID.equals(current.getUserID()))
					.orElseThrow(denied);
			accounts.put(userID, account);
		}

		return accounts;
	}
}
