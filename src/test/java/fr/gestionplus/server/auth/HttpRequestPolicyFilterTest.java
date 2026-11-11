package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.FormContentFilter;

import fr.gestionplus.server.audit.AuditAction;
import fr.gestionplus.server.audit.AuditMetadata;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditResult;
import fr.gestionplus.server.audit.AuditService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;

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

		ArgumentCaptor<ServletRequest> forwarded = ArgumentCaptor.forClass(ServletRequest.class);
		verify(chain).doFilter(forwarded.capture(), org.mockito.ArgumentMatchers.same(response));
		assertEquals(password, forwarded.getValue().getParameter("password"));
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

	@Test
	void anUnauthenticatedPrincipalCannotBeUsedToAttributeARefusal() throws Exception {
		UserAccount unverified = new UserAccount(UUID.randomUUID(), "unverified", UserAccountStatus.ACTIVE,
				Set.of(), UUID.randomUUID(), "Exemple", "Test", "test@example.invalid", "0600000000");
		SecurityContextHolder.getContext()
				.setAuthentication(UsernamePasswordAuthenticationToken.unauthenticated(unverified, null));
		MockHttpServletRequest request = login();
		request.setContent(new byte[16385]);

		assertRejected(request, 413, "PAYLOAD_TOO_LARGE");

		verify(audit).recordForActor(AuditAction.AUTH_ACCESS_ATTEMPT, AuditResult.DENIED, null, "127.0.0.1",
				Set.of(), AuditMetadata.because(AuditReason.INVALID_INPUT));
	}

	@ParameterizedTest
	@ValueSource(strings = { "application/json", "application/x-www-form-urlencoded" })
	void countsChunkedBodiesEvenWhenTheApplicationWouldNotReadThem(String type) throws Exception {
		MockHttpServletRequest request = unknownLength("/api/unused", type, new byte[16385]);
		assertRejected(request, 413, "PAYLOAD_TOO_LARGE");
	}

	@ParameterizedTest
	@ValueSource(strings = { "PUT", "PATCH", "DELETE" })
	void rejectsOversizedChunkedFormsBeforeTheSpringFormFilter(String method) throws Exception {
		MockHttpServletRequest request = unknownLength("/api/unused", "application/x-www-form-urlencoded",
				("field=" + "x".repeat(16385)).getBytes(StandardCharsets.UTF_8));
		request.setMethod(method);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response, (forwarded, returned) -> {
			throw new AssertionError("Le formulaire trop volumineux ne doit pas atteindre le parseur Spring.");
		});

		assertEquals(413, response.getStatus());
		assertEquals("{\"code\":\"PAYLOAD_TOO_LARGE\"}", response.getContentAsString());
	}

	@ParameterizedTest
	@ValueSource(strings = { "PUT", "PATCH", "DELETE" })
	void preservesNormalChunkedFormsForTheSpringFormFilter(String method) throws Exception {
		MockHttpServletRequest request = unknownLength("/api/unused", "application/x-www-form-urlencoded",
				"field=+a%2Bb+".getBytes(StandardCharsets.UTF_8));
		request.setMethod(method);

		filter.doFilter(request, new MockHttpServletResponse(), (forwarded, returned) -> {
			new FormContentFilter().doFilter(forwarded, returned, (parsed, response) -> {
				assertEquals(" a+b ", parsed.getParameter("field"));
				assertArrayEquals(new String[] { " a+b " }, parsed.getParameterValues("field"));
			});
		});

		verifyNoInteractions(audit);
	}

	@ParameterizedTest
	@ValueSource(strings = { "PUT", "PATCH", "DELETE" })
	void boundsFormParametersBeforeTheSpringFormFilter(String method) throws Exception {
		MockHttpServletRequest request = unknownLength("/api/unused", "application/x-www-form-urlencoded",
				("field=value&".repeat(32) + "field=value").getBytes(StandardCharsets.UTF_8));
		request.setMethod(method);

		assertRejected(request, 400, "INVALID_REQUEST");
	}

	@Test
	void preservesAnUnknownLengthBodyExactlyAtTheLimit() throws Exception {
		byte[] content = "x".repeat(16384).getBytes(StandardCharsets.UTF_8);
		MockHttpServletRequest request = unknownLength("/api/unused", "application/json", content);
		MockHttpServletResponse response = new MockHttpServletResponse();

		filter.doFilter(request, response,
				(forwarded, returned) -> assertArrayEquals(content, forwarded.getInputStream().readAllBytes()));

		assertEquals(200, response.getStatus());
		verifyNoInteractions(audit);
	}

	@Test
	void decodesChunkedFormsWithoutConsumingTheForwardedBody() throws Exception {
		String form = "username=Alice&password=+a%2Bb+&totp=012345";
		MockHttpServletRequest request = unknownLength("/login", "application/x-www-form-urlencoded",
				form.getBytes(StandardCharsets.UTF_8));

		filter.doFilter(request, new MockHttpServletResponse(), (forwarded, response) -> {
			assertEquals("Alice", forwarded.getParameter("username"));
			assertEquals(" a+b ", forwarded.getParameter("password"));
			assertEquals("012345", forwarded.getParameter("totp"));
			assertEquals(form, new String(forwarded.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
		});
		verifyNoInteractions(audit);
	}

	@Test
	void preservesUtf8ReaderAndServletReaderExclusivity() throws Exception {
		MockHttpServletRequest request = unknownLength("/api/unused", "application/json",
				"été".getBytes(StandardCharsets.UTF_8));
		request.setCharacterEncoding("UTF-8");
		filter.doFilter(request, new MockHttpServletResponse(), (forwarded, response) -> {
			assertEquals("été", forwarded.getReader().readLine());
			assertThrows(IllegalStateException.class, forwarded::getInputStream);
		});
	}

	@Test
	void preservesTheServletDefaultCharsetWhenNoEncodingWasSpecified() throws Exception {
		MockHttpServletRequest request = unknownLength("/api/unused", "application/json", new byte[] { (byte) 0xE9 });
		filter.doFilter(request, new MockHttpServletResponse(),
				(forwarded, response) -> assertEquals("é", forwarded.getReader().readLine()));
	}

	@Test
	void rejectsDuplicateAndMalformedFieldsInRawChunkedForms() throws Exception {
		assertRejected(unknownLength("/login", "application/x-www-form-urlencoded",
				"password=one&password=two".getBytes(StandardCharsets.UTF_8)), 400, "INVALID_REQUEST");
		assertRejected(unknownLength("/login", "application/x-www-form-urlencoded",
				"password=%ZZ".getBytes(StandardCharsets.UTF_8)), 400, "INVALID_REQUEST");
	}

	@Test
	void rejectsTooManyRawParametersAndBodiesWithoutAContentType() throws Exception {
		assertRejected(unknownLength("/login", "application/x-www-form-urlencoded",
				("a=1&".repeat(32) + "a=1").getBytes(StandardCharsets.UTF_8)), 400, "INVALID_REQUEST");
		assertRejected(unknownLength("/login", null, "password=secret".getBytes(StandardCharsets.UTF_8)),
				415, "UNSUPPORTED_MEDIA_TYPE");
	}

	private MockHttpServletRequest unknownLength(String path, String type, byte[] body) {
		MockHttpServletRequest request = new MockHttpServletRequest("POST", path) {
			@Override
			public long getContentLengthLong() {
				return -1;
			}

			@Override
			public int getContentLength() {
				return -1;
			}
		};
		request.setContentType(type);
		request.setContent(body);
		request.addHeader("Transfer-Encoding", "chunked");
		return request;
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
