package fr.gestionplus.server.api;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import fr.gestionplus.server.account.AccountActivationConflictException;
import fr.gestionplus.server.account.AccountActivationService;
import fr.gestionplus.server.account.InvitationDeliveryPendingException;
import fr.gestionplus.server.audit.AuditService;

class AccountActivationControllerTest {

	private static final String PATH = "/api/invitations/{invitationID}/sms-challenge";
	private static final UUID INVITATION_ID = UUID.randomUUID();
	private static final String TOKEN = "a".repeat(43);
	private static final String BODY = "{\"token\":\"" + TOKEN + "\"}";

	private final AccountActivationService activationService = mock(AccountActivationService.class);
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new AccountActivationController(activationService))
				.setControllerAdvice(new ApiExceptionHandler(mock(AuditService.class))).build();
	}

	@Test
	void delegatesTheInvitationIdAndTokenAndReturnsAnEmptyResponse() throws Exception {
		mvc.perform(smsChallengeRequest())
				.andExpect(status().isOk())
				.andExpect(content().string(""));

		verify(activationService).requestActivationChallenge(INVITATION_ID, TOKEN);
	}

	@Test
	void rejectsAnInvalidInvitationIdBeforeCallingTheService() throws Exception {
		mvc.perform(post(PATH, "invalid-id").contentType(MediaType.APPLICATION_JSON).content(BODY))
				.andExpect(status().isBadRequest())
				.andExpect(content().string("{\"code\":\"INVALID_REQUEST\"}"));

		verifyNoInteractions(activationService);
	}

	@Test
	void rejectsMalformedJsonBeforeCallingTheService() throws Exception {
		mvc.perform(post(PATH, INVITATION_ID).contentType(MediaType.APPLICATION_JSON).content("{"))
				.andExpect(status().isBadRequest())
				.andExpect(content().string("{\"code\":\"INVALID_REQUEST\"}"));

		verifyNoInteractions(activationService);
	}

	@Test
	void returnsForbiddenWhenTheServiceRefusesAnAuthenticatedRequest() throws Exception {
		doThrow(new AccessDeniedException("Jeton refusé.")).when(activationService)
				.requestActivationChallenge(INVITATION_ID, TOKEN);

		mvc.perform(smsChallengeRequest().principal(
				UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of())))
				.andExpect(status().isForbidden())
				.andExpect(content().string("{\"code\":\"FORBIDDEN\"}"));
	}

	@Test
	void returnsUnauthorizedWhenTheServiceRefusesAnAnonymousRequest() throws Exception {
		doThrow(new AccessDeniedException("Jeton refusé.")).when(activationService)
				.requestActivationChallenge(INVITATION_ID, TOKEN);

		mvc.perform(smsChallengeRequest())
				.andExpect(status().isUnauthorized())
				.andExpect(content().string("{\"code\":\"AUTHENTICATION_REQUIRED\"}"));
	}

	@Test
	void returnsConflictWhenTheServiceRefusesToCreateAnotherChallenge() throws Exception {
		doThrow(new AccountActivationConflictException()).when(activationService)
				.requestActivationChallenge(INVITATION_ID, TOKEN);

		mvc.perform(smsChallengeRequest())
				.andExpect(status().isConflict())
				.andExpect(content().string("{\"code\":\"CONFLICT\"}"));
	}

	@Test
	void returnsServiceUnavailableWhenInvitationDeliveryIsPending() throws Exception {
		doThrow(new InvitationDeliveryPendingException()).when(activationService)
				.requestActivationChallenge(INVITATION_ID, TOKEN);

		mvc.perform(smsChallengeRequest())
				.andExpect(status().isServiceUnavailable())
				.andExpect(content().string("{\"code\":\"SERVICE_UNAVAILABLE\"}"));
	}

	private MockHttpServletRequestBuilder smsChallengeRequest() {
		return post(PATH, INVITATION_ID).contentType(MediaType.APPLICATION_JSON).content(BODY);
	}
}
