package fr.gestionplus.server.api;

import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import jakarta.servlet.RequestDispatcher;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

class ApiExceptionHandlerTest {

	private static final String PRIVATE_DETAIL = "password=private-test-value tenant=private-tenant-id";
	private MockMvc mvc;

	@BeforeEach
	void setUp() {
		mvc = MockMvcBuilders.standaloneSetup(new ErrorTestController(), new ApiErrorController())
				.setControllerAdvice(new ApiExceptionHandler(mock(AuditService.class))).build();
	}

	@ParameterizedTest
	@CsvSource({ "authentication,401,AUTHENTICATION_REQUIRED", "stale,401,AUTHENTICATION_REQUIRED",
			"permission,403,FORBIDDEN",
			"tenant,404,NOT_FOUND", "structure,404,NOT_FOUND", "missing,404,NOT_FOUND",
			"input,400,INVALID_REQUEST", "conflict,409,CONFLICT", "unavailable,503,SERVICE_UNAVAILABLE",
			"unexpected,500,INTERNAL_ERROR" })
	void returnsOnlyAPublicCodeForEachError(String kind, int expectedStatus, String code) throws Exception {
		mvc.perform(get("/test/errors/" + kind).principal(
				UsernamePasswordAuthenticationToken.authenticated("test-user", null, List.of())))
				.andExpect(status().is(expectedStatus))
				.andExpect(content().string("{\"code\":\"" + code + "\"}"))
				.andExpect(header().string("Cache-Control", "no-store"));
	}

	@Test
	void aPermissionFailureWithoutAuthenticationReturns401() throws Exception {
		mvc.perform(get("/test/errors/permission")).andExpect(status().isUnauthorized())
				.andExpect(content().string("{\"code\":\"AUTHENTICATION_REQUIRED\"}"));
	}

	@Test
	void validationAndMalformedJsonDoNotEchoSubmittedValues() throws Exception {
		mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON)
				.content("{\"value\":\"" + PRIVATE_DETAIL + "\"}"))
				.andExpect(status().isBadRequest()).andExpect(content().string("{\"code\":\"INVALID_REQUEST\"}"));
		mvc.perform(post("/test/input").contentType(MediaType.APPLICATION_JSON).content("{invalid"))
				.andExpect(status().isBadRequest()).andExpect(content().string("{\"code\":\"INVALID_REQUEST\"}"));
	}

	@Test
	void missingAndInvalidParametersUseTheSameGenericInputError() throws Exception {
		mvc.perform(get("/test/parameter")).andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		mvc.perform(get("/test/parameter").param("value", PRIVATE_DETAIL)).andExpect(status().isBadRequest())
				.andExpect(content().string("{\"code\":\"INVALID_REQUEST\"}"));
	}

	@Test
	void servletErrorDispatchDoesNotExposeExceptionOrOriginalPath() throws Exception {
		mvc.perform(get("/error").requestAttr(RequestDispatcher.ERROR_STATUS_CODE, 500)
				.requestAttr(RequestDispatcher.ERROR_EXCEPTION, new IllegalStateException(PRIVATE_DETAIL))
				.requestAttr(RequestDispatcher.ERROR_MESSAGE, PRIVATE_DETAIL)
				.requestAttr(RequestDispatcher.ERROR_REQUEST_URI, "/private/" + UUID.randomUUID()))
				.andExpect(status().isInternalServerError())
				.andExpect(content().string("{\"code\":\"INTERNAL_ERROR\"}"));
	}

	@TestComponent
	@RestController
	static class ErrorTestController {

		@GetMapping("/test/errors/{kind}")
		void fail(@PathVariable("kind") String kind) {

			switch (kind) {
			case "authentication" -> throw new BadCredentialsException(PRIVATE_DETAIL);
			case "permission" -> throw new AccessDeniedException(PRIVATE_DETAIL);
			case "stale" -> throw new AuditedAccessDeniedException(PRIVATE_DETAIL, AuditReason.STALE_SESSION);
			case "tenant" -> throw new AuditedAccessDeniedException(PRIVATE_DETAIL, AuditReason.OUTSIDE_TENANT);
			case "structure" -> throw new AuditedAccessDeniedException(PRIVATE_DETAIL, AuditReason.OUTSIDE_STRUCTURE);
			case "missing" -> throw new ResponseStatusException(HttpStatus.NOT_FOUND, PRIVATE_DETAIL);
			case "input" -> throw new IllegalArgumentException(PRIVATE_DETAIL);
			case "conflict" -> throw new ConcurrencyFailureException(PRIVATE_DETAIL);
			case "unavailable" -> throw new DataAccessResourceFailureException(PRIVATE_DETAIL);
			default -> throw new IllegalStateException(PRIVATE_DETAIL);
			}

		}

		@PostMapping("/test/input")
		void input(@Valid @RequestBody Input value) {
		}

		@GetMapping("/test/parameter")
		void parameter(@RequestParam("value") UUID value) {
		}
	}

	record Input(@Size(max = 5) String value) {
	}
}
