package fr.gestionplus.server.account;

import java.util.Objects;
import java.util.UUID;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import fr.gestionplus.server.auth.AuthorizationService;
import fr.gestionplus.server.auth.Permission;
import fr.gestionplus.server.auth.UserAccount;

@Service
public class AccountManagementService {

	private final AccountStore store;

	public AccountManagementService(AccountStore store) {
		this.store = Objects.requireNonNull(store);
	}

	public void disableAccount(UUID actorUserId, UUID targetUserId) {
		UserAccount actorUser = store.findById(actorUserId).orElseThrow();

		if (!AuthorizationService.can(actorUser, Permission.MANAGE_USERS)) {
		    throw new AccessDeniedException("Permission insuffisante");
		}
		
		UserAccount targetUser = store.findById(targetUserId).orElseThrow();
	}
}
