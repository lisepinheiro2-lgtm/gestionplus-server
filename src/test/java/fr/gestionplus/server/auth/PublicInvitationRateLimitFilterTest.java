package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.Set;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import jakarta.servlet.FilterChain;

class PublicInvitationRateLimitFilterTest {

	private static final String INVITATION_ID = "00000000-0000-0000-0000-000000000001";
	private static final String SCOPE = "invitation-activation";
	private static final String ADDRESS = "192.0.2.10";
	private static final Duration WINDOW = Duration.ofMinutes(15);
	private final RequestRateLimiter limiter = mock(RequestRateLimiter.class);
	private final AuditService audit = mock(AuditService.class);
	private final FilterChain chain = mock(FilterChain.class);
	private final PublicInvitationRateLimitFilter filter = new PublicInvitationRateLimitFilter(
			new HttpSecurityAudit(audit), limiter);

	@ParameterizedTest
	@MethodSource("activationPaths")
	void countsCanonicalAndEncodedPathsWithOrWithoutAContextPath(String contextPath, String path) throws Exception {
		MockHttpServletRequest request = request("POST", contextPath + path);
		request.setContextPath(contextPath);
		request.addHeader("X-Forwarded-For", "198.51.100.20");
		when(limiter.acquire(SCOPE, ADDRESS, 30, WINDOW))
				.thenReturn(new RequestRateLimiter.Decision(true, Duration.ZERO));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(limiter).acquire(SCOPE, ADDRESS, 30, WINDOW);
		verify(chain).doFilter(request, response);
		verifyNoInteractions(audit);
	}

	@ParameterizedTest
	@CsvSource({ "GET,sms-challenge", "PUT,sms-challenge", "DELETE,activation", "POST,sms-challenges",
			"POST,sms-challenge/extra", "POST,activation/extra", "POST,other" })
	void ignoresMethodsAndPathsOutsideThePublicActivationRoutes(String method, String suffix) throws Exception {
		MockHttpServletRequest request = request(method, "/api/invitations/" + INVITATION_ID + "/" + suffix);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		verifyNoInteractions(limiter, audit);
	}

	@ParameterizedTest
	@CsvSource({ "1,1", "800,1", "1000,1", "1001,2", "899999,900", "900000,900" })
	void rejectsRequestsWithACeilingRetryDelayAndAGenericAuditedResponse(long milliseconds, String seconds)
			throws Exception {
		MockHttpServletRequest request = request("POST", "/api/invitations/" + INVITATION_ID + "/sms-challenge");
		when(limiter.acquire(SCOPE, ADDRESS, 30, WINDOW))
				.thenReturn(new RequestRateLimiter.Decision(false, Duration.ofMillis(milliseconds)));
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		assertEquals(429, response.getStatus());
		assertEquals(seconds, response.getHeader("Retry-After"));
		assertEquals("no-store", response.getHeader("Cache-Control"));
		assertEquals("{\"code\":\"TOO_MANY_REQUESTS\"}", response.getContentAsString());
		verifyNoInteractions(chain);
		verify(audit).recordForActor(AuditAction.AUTH_ACCESS_ATTEMPT, AuditResult.DENIED, null, ADDRESS,
				Set.of(), AuditMetadata.because(AuditReason.RATE_LIMITED));
	}

	@Test
	void propagatesUnavailableSharedSecurityWithoutPassingTheRequestToTheApplication() {
		MockHttpServletRequest request = request("POST", "/api/invitations/" + INVITATION_ID + "/activation");
		when(limiter.acquire(anyString(), anyString(), anyInt(), eq(WINDOW)))
				.thenThrow(new DistributedSecurityUnavailableException());

		assertThrows(DistributedSecurityUnavailableException.class,
				() -> filter.doFilter(request, new MockHttpServletResponse(), chain));

		verifyNoInteractions(chain, audit);
	}

	private MockHttpServletRequest request(String method, String path) {
		MockHttpServletRequest request = new MockHttpServletRequest(method, path);
		request.setRemoteAddr(ADDRESS);
		return request;
	}

	private static Stream<Arguments> activationPaths() {
		return Stream.of("", "/fictitious-context").flatMap(contextPath -> Stream.of(
				"/api/invitations/" + INVITATION_ID + "/sms-challenge",
				"/api/invitations/" + INVITATION_ID + "/activation",
				"/api/%69nvitations/" + INVITATION_ID + "/sms-challenge",
				"/%61pi/invitations/" + INVITATION_ID + "/activation",
				"/api/invitations/" + INVITATION_ID + "/%73ms-challenge",
				"/api/invitations/" + INVITATION_ID + "/%61ctivation")
				.map(path -> Arguments.of(contextPath, path)));
	}
}
