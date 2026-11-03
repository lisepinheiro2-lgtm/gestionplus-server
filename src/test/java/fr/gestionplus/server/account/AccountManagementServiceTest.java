
package fr.gestionplus.server.account;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

class AccountManagementServiceTest {

	@Test
	void employeeCannotDisableAnotherAccount() {

		UUID actorUserId = UUID.randomUUID();
		UUID targetUserId = UUID.randomUUID();

		UserAccount actorUser = new UserAccount(actorUserId, "alice", UserAccountStatus.ACTIVE,
				Set.of(UserRole.EMPLOYEE), UUID.randomUUID());

		AccountStore store = mock(AccountStore.class);

		when(store.findById(actorUserId)).thenReturn(Optional.of(actorUser));

		AccountManagementService service = new AccountManagementService(store);
		assertThrows(AccessDeniedException.class, () -> service.disableAccount(actorUserId, targetUserId));

		verify(store).findById(actorUserId);
		verify(store, never()).findById(targetUserId);
		verify(store, never()).disableAccount(targetUserId);
	}

	@Test
	void cannotDisableUnknownAccount() {

		UUID actorUserId = UUID.randomUUID();
		UUID targetUserId = UUID.randomUUID();
		UserAccount admin = new UserAccount(actorUserId, "admin", UserAccountStatus.ACTIVE, Set.of(UserRole.ADMIN),
				UUID.randomUUID());

		AccountStore store = mock(AccountStore.class);

		when(store.findById(actorUserId)).thenReturn(Optional.of(admin));
		when(store.findById(targetUserId)).thenReturn(Optional.empty());

		AccountManagementService service = new AccountManagementService(store);

		assertThrows(java.util.NoSuchElementException.class, () -> service.disableAccount(actorUserId, targetUserId));

		verify(store).findById(actorUserId);
		verify(store).findById(targetUserId);
		verify(store, never()).disableAccount(targetUserId);
	}

}
