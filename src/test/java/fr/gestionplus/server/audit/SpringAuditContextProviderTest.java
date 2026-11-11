package fr.gestionplus.server.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

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
				UUID.randomUUID(), "Exemple", "Test", "test@example.invalid", "0600000000");
		SpringAuditContextProvider context = new SpringAuditContextProvider();
		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(account, null, List.of());
		SecurityContextHolder.getContext().setAuthentication(authentication);

		assertEquals(userID, context.getActorUserId());
	}

	@Test
	void usesTheContainerAddressInsteadOfRawForwardingHeaders() {
		MockHttpServletRequest request = new MockHttpServletRequest();
		request.setRemoteAddr("192.0.2.10");
		request.addHeader("X-Forwarded-For", "203.0.113.77");
		request.addHeader("Forwarded", "for=203.0.113.77");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));

		assertEquals("192.0.2.10", new SpringAuditContextProvider().getIpAddress());
	}

	@Test
	void returnNullWhenIsNotUserAccount() {

		Authentication authentication = UsernamePasswordAuthenticationToken.authenticated("testUser", null, List.of());
		SpringAuditContextProvider context = new SpringAuditContextProvider();
		SecurityContextHolder.getContext().setAuthentication(authentication);

		assertNull(context.getActorUserId());
	}
}
