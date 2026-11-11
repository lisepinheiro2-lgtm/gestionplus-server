package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.jayway.jsonpath.JsonPath;

import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_chunked_http_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true", "gestionplus.activation.expiry-enabled=false",
		"gestionplus.notifications.from=false" })
class ChunkedHttpRequestIntegrationTest extends AuditStorageIntegrationTest {

	@Value("${local.server.port}")
	private int port;

	@MockitoBean
	private LoginService login;

	@Test
	void rejectsOversizedChunkedBodiesBeforeCsrfOrAuthentication() throws Exception {

		try (HttpClient client = client()) {
			HttpResponse<String> response = client.send(post("/login", "application/x-www-form-urlencoded",
					new byte[16385], null), HttpResponse.BodyHandlers.ofString());

			assertEquals(413, response.statusCode());
			assertEquals("{\"code\":\"PAYLOAD_TOO_LARGE\"}", response.body());
			verifyNoInteractions(login);
		}

	}

	@Test
	void countsChunkedJsonBodiesOnRoutesWhoseHandlerWouldNotReadTheBody() throws Exception {

		try (HttpClient client = client()) {
			HttpResponse<String> response = client.send(post("/api/unused", "application/json",
					new byte[16385], null), HttpResponse.BodyHandlers.ofString());

			assertEquals(413, response.statusCode());
			verifyNoInteractions(login);
		}

	}

	@ParameterizedTest
	@ValueSource(strings = { "PUT", "PATCH", "DELETE" })
	void boundsChunkedFormsBeforeSpringParsesThem(String method) throws Exception {
		byte[] body = ("field=" + "x".repeat(16385)).getBytes(StandardCharsets.UTF_8);
		HttpRequest request = HttpRequest.newBuilder(uri("/api/unused")).timeout(Duration.ofSeconds(10))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.method(method, HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build();

		try (HttpClient client = client()) {
			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertEquals(413, response.statusCode());
			assertEquals("{\"code\":\"PAYLOAD_TOO_LARGE\"}", response.body());
			verifyNoInteractions(login);
		}

	}

	@Test
	void normalChunkedFormParametersReachAuthenticationUnchanged() throws Exception {
		when(login.evaluate("chunked-user", " a+b ")).thenReturn(
				new LoginService.LoginAttempt(null, null, AuditReason.INVALID_CREDENTIALS));

		try (HttpClient client = client()) {
			HttpResponse<String> csrf = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf"))
					.GET().build(), HttpResponse.BodyHandlers.ofString());
			assertEquals(200, csrf.statusCode());
			String token = JsonPath.read(csrf.body(), "$.token");
			byte[] body = "username=chunked-user&password=+a%2Bb+".getBytes(StandardCharsets.UTF_8);

			HttpResponse<String> response = client.send(post("/login",
					"application/x-www-form-urlencoded; charset=UTF-8", body, token),
					HttpResponse.BodyHandlers.ofString());

			assertEquals(401, response.statusCode());
			verify(login).evaluate("chunked-user", " a+b ");
			assertTrue(response.body().contains("AUTHENTICATION_REQUIRED"));
		}

	}

	private HttpClient client() {
		return HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1)
				.cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
				.connectTimeout(Duration.ofSeconds(5)).build();
	}

	private HttpRequest post(String path, String type, byte[] body, String csrf) {
		HttpRequest.Builder request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10))
				.header("Content-Type", type)
				.POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body)));

		if (csrf != null) {
			request.header("X-CSRF-TOKEN", csrf);
		}

		return request.build();
	}

	private URI uri(String path) {
		return URI.create("http://localhost:" + port + path);
	}
}
