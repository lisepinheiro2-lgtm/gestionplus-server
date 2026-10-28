package fr.gestionplus.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;

import fr.gestionplus.server.auth.UserAccount;
import fr.gestionplus.server.auth.UserAccountStatus;
import fr.gestionplus.server.auth.UserRole;

public class SpringAuditContextProviderTest {

	@Test
	void returnsNullWhenNoUserAuthenticated() {

		SpringAuditContextProvider context = new SpringAuditContextProvider();

		assertNull(context.getActorUserId());
	}

	@Test
	void notAutorised() {

		Authentication authentication = UsernamePasswordAuthenticationToken.unauthenticated("testUser", "fakePassword");
		SpringAuditContextProvider context = new SpringAuditContextProvider();
		SecurityContextHolder.getContext().setAuthentication(authentication);

		assertNull(context.getActorUserId());
	}

	@AfterEach
	void cleanSecurityContext() {
		SecurityContextHolder.clearContext();
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	void returnsUserIdWhenAuthenticated() {

		UUID userID = UUID.randomUUID();
		UserAccount account = new UserAccount(userID, "test", UserAccountStatus.ACTIVE, Set.of(UserRole.SECRETARY),
				UUID.randomUUID());
		SpringAuditContextProvider context = new SpringAuditContextProvider();
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(account, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);

		assertEquals(userID, context.getActorUserId());
	}

	@Test
	void returnNullWhenIsNotUserAccount() {
		
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated("testUser", null, List.of());
		SpringAuditContextProvider context = new SpringAuditContextProvider();
		SecurityContextHolder.getContext().setAuthentication(authentication);

		assertNull(context.getActorUserId());
	}
}
