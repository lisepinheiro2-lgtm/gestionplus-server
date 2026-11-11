package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Set;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import jakarta.servlet.FilterChain;

class HttpRequestPolicyFilterTest {

	private final AuditService audit = mock(AuditService.class);
	private final HttpRequestPolicyFilter filter = new HttpRequestPolicyFilter(new HttpSecurityAudit(audit), false,
			16384);
	private final FilterChain chain = mock(FilterChain.class);

	@AfterEach
	void clearAuthentication() {
		SecurityContextHolder.clearContext();
	}

	@ParameterizedTest
	@CsvSource({ "username,256", "password,129", "totp,7" })
	void rejectsExcessivelyLongFieldsBeforeAuthentication(String field, int length) throws Exception {
		MockHttpServletRequest request = login();
		request.addParameter(field, "x".repeat(length));
		assertRejected(request, 400, "INVALID_REQUEST");
	}

	@Test
	void rejectsDuplicateCredentialsAndCredentialsInTheQueryString() throws Exception {
		MockHttpServletRequest duplicates = login();
		duplicates.addParameter("password", "first", "second");
		assertRejected(duplicates, 400, "INVALID_REQUEST");

		MockHttpServletRequest query = login();
		query.setQueryString("password=private-test-value");
		assertRejected(query, 400, "INVALID_REQUEST");
	}

	@Test
	void rejectsALargeBodyWithoutPassingItToTheApplication() throws Exception {
		MockHttpServletRequest request = login();
		request.setContent(new byte[16385]);
		assertRejected(request, 413, "PAYLOAD_TOO_LARGE");
	}

	@Test
	void preservesThePasswordWithoutTrimmingOrTruncation() throws Exception {
		MockHttpServletRequest request = login();
		String password = " " + "x".repeat(126) + " ";
		request.addParameter("username", "u".repeat(255));
		request.addParameter("password", password);
		request.addParameter("totp", "012345");
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, chain);

		verify(chain).doFilter(request, response);
		assertEquals(password, request.getParameter("password"));
		verifyNoInteractions(audit);
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/json", "multipart/form-data; boundary=test", "*/*" })
	void rejectsUnsupportedAuthenticationBodies(String type) throws Exception {
		MockHttpServletRequest request = login();
		request.setContentType(type);
		assertRejected(request, 415, "UNSUPPORTED_MEDIA_TYPE");
	}

	@Test
	void sanitizesAChunkedFormRejectedByTheServletParser() throws Exception {
		MockHttpServletRequest request = spy(login());
		request.addHeader("Transfer-Encoding", "chunked");
		when(request.getParameterMap()).thenThrow(new InvalidParameterException("private parser detail", 413));
		assertRejected(request, 413, "PAYLOAD_TOO_LARGE");
	}

	@Test
	void forwardedHeadersCannotBypassHttpsOrReplaceTheAuditedAddress() throws Exception {
		HttpRequestPolicyFilter production = new HttpRequestPolicyFilter(new HttpSecurityAudit(audit), true, 16384);
		MockHttpServletRequest request = login();
		request.setRemoteAddr("192.0.2.10");
		request.addHeader("Forwarded", "for=198.51.100.20;proto=https");
		request.addHeader("X-Forwarded-Proto", "https");
		request.addHeader("X-Forwarded-For", "198.51.100.20");
		MockHttpServletResponse response = new MockHttpServletResponse();

		production.doFilter(request, response, chain);

		assertEquals(403, response.getStatus());
		assertEquals("{\"code\":\"HTTPS_REQUIRED\"}", response.getContentAsString());
		verifyNoInteractions(chain);
		verify(audit).recordForActor(AuditAction.AUTH_ACCESS_ATTEMPT, AuditResult.DENIED, null, "192.0.2.10",
				Set.of(), AuditMetadata.because(AuditReason.INSECURE_TRANSPORT));
	}

	private MockHttpServletRequest login() {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", "/login");
		request.setContentType(MediaType.APPLICATION_FORM_URLENCODED_VALUE);
		return request;
	}

	private void assertRejected(MockHttpServletRequest request, int status, String code) throws Exception {
		MockHttpServletResponse response = new MockHttpServletResponse();
		filter.doFilter(request, response, chain);
		assertEquals(status, response.getStatus());
		assertEquals("{\"code\":\"" + code + "\"}", response.getContentAsString());
		assertEquals("no-store", response.getHeader("Cache-Control"));
		verifyNoInteractions(chain);
	}
}
