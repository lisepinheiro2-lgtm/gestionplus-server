package fr.gestionplus.server.api;

import static fr.gestionplus.server.account.SmsIdentityChallenge.Purpose.ACCOUNT_ACTIVATION;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.gestionplus.server.account.AccountInvitationStore;
import fr.gestionplus.server.account.AccountInvitationStore.Invitation;
import fr.gestionplus.server.account.SmsIdentityChallengeStore;
import fr.gestionplus.server.auth.CompromisedPasswordChecker;
import fr.gestionplus.server.support.AuditStorageIntegrationTest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
		"spring.datasource.url=jdbc:tc:postgresql:16-alpine:///gestionplus_activation_http_test",
		"spring.datasource.driver-class-name=org.testcontainers.jdbc.ContainerDatabaseDriver",
		"spring.datasource.username=test", "spring.datasource.password=test", "spring.jpa.hibernate.ddl-auto=validate",
		"spring.flyway.enabled=true", "server.address=127.0.0.1", "gestionplus.activation.expiry-enabled=false",
		"gestionplus.activation.challenge-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=" })
class ActivationHttpPostgresqlIntegrationTest extends AuditStorageIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PASSWORD = "Fictitious-Activation-Password-2026!";
	@Autowired
	private JdbcTemplate jdbc;
	@Autowired
	private AccountInvitationStore invitations;
	@Autowired
	private SmsIdentityChallengeStore challenges;
	@MockitoBean
	private CompromisedPasswordChecker checker;
	@Value("${local.server.port}")
	private int port;
	private final AtomicReference<String> sentCode = new AtomicReference<>();

	@BeforeEach
	void prohibitExternalCallsAndCaptureOnlyTheFictitiousSms() {
		when(checker.isCompromised(anyString())).thenReturn(false);
		doAnswer(call -> {
			sentCode.set(call.getArgument(2));
			return null;
		}).when(messageSender).send(eq(ACCOUNT_ACTIVATION), anyString(), anyString());
	}

	@Test
	void completesActivationOverHttpThenAllowsANormalLoginWithoutCreatingAnActivationSession() throws Exception {
		Fixture fixture = fixture();

		try (var client = client()) {
			JsonNode csrf = csrf(client);
			assertEquals(200, post(client, path(fixture, "/sms-challenge"), body(fixture, null), csrf).statusCode());
			assertNotNull(sentCode.get());
			assertNotNull(challenges.findCurrentByInvitationAndUser(fixture.invitationID(), fixture.userID())
					.orElseThrow().sendConfirmedAt());
			assertEquals(401, get(client, "/api/auth/me").statusCode());
			String request = body(fixture, sentCode.get());
			assertEquals(200, post(client, path(fixture, "/activation"), request, csrf).statusCode());
			assertEquals("ACTIVE", jdbc.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?",
					String.class, fixture.userID()));
			assertEquals(401, get(client, "/api/auth/me").statusCode());
			HttpResponse<String> replay = post(client, path(fixture, "/activation"), request, csrf);
			assertEquals(403, replay.statusCode());
			assertEquals("ACTIVATION_DENIED", JSON.readTree(replay.body()).get("code").asString());
			JsonNode loginCsrf = csrf(client);
			HttpRequest login = HttpRequest.newBuilder(base().resolve("/login")).timeout(Duration.ofSeconds(15))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.header(loginCsrf.get("headerName").asString(), loginCsrf.get("token").asString())
					.POST(HttpRequest.BodyPublishers.ofString("username=" + fixture.userName() + "&password="
							+ URLEncoder.encode(PASSWORD, StandardCharsets.UTF_8)))
					.build();
			assertEquals(200, client.send(login, HttpResponse.BodyHandlers.ofString()).statusCode());
			assertEquals(fixture.userName(),
					JSON.readTree(get(client, "/api/auth/me").body()).get("userName").asString());
		}

	}

	@Test
	void threeWrongCodesRevokeTheChallengeAndKeepTheSmsAttemptInHistory() throws Exception {
		Fixture fixture = fixture();

		try (var client = client()) {
			JsonNode csrf = csrf(client);
			assertEquals(200, post(client, path(fixture, "/sms-challenge"), body(fixture, null), csrf).statusCode());
			String wrongCode = "000000".equals(sentCode.get()) ? "000001" : "000000";

			for (int attempt = 0; attempt < 3; attempt++) {
				HttpResponse<String> rejected = post(client, path(fixture, "/activation"), body(fixture, wrongCode),
						csrf);
				assertEquals(403, rejected.statusCode());
				assertEquals("ACTIVATION_DENIED", JSON.readTree(rejected.body()).get("code").asString());
			}

			assertTrue(challenges.findCurrentByInvitationAndUser(fixture.invitationID(), fixture.userID()).isEmpty());
			assertEquals(1, challenges.countByInvitationAndUser(fixture.invitationID(), fixture.userID()));
			assertEquals("PENDING_ACTIVATION", jdbc.queryForObject("SELECT status FROM user_accounts WHERE user_id = ?",
					String.class, fixture.userID()));
		}

	}

	private Fixture fixture() {
		UUID ownerID = account(false, null);
		UUID tenantID = UUID.randomUUID();
		UUID structureID = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, 'Fictitious HTTP activation', ?)",
				tenantID, ownerID);
		jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", ownerID, tenantID);
		jdbc.update(
				"INSERT INTO organizational_structures (structure_id, structure_name, tenant_id) VALUES (?, 'Fictitious structure', ?)",
				structureID, tenantID);
		UUID userID = account(true, structureID);
		jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", userID, tenantID);
		jdbc.update("INSERT INTO structure_memberships (user_id, structure_id, tenant_id) VALUES (?, ?, ?)", userID,
				structureID, tenantID);
		jdbc.update("INSERT INTO structure_membership_roles (user_id, structure_id, role) VALUES (?, ?, 'EMPLOYEE')",
				userID, structureID);
		byte[] bytes = new byte[32];
		new java.security.SecureRandom().nextBytes(bytes);
		String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		UUID invitationID = UUID.randomUUID();
		String digest = java.util.HexFormat.of().formatHex(sha256(token));
		invitations
				.save(new Invitation(invitationID, userID, structureID, digest, Instant.now().plusSeconds(3600), null));
		return new Fixture(invitationID, userID, "fictitious_" + userID, token);
	}

	private byte[] sha256(String value) {

		try {
			return java.security.MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
		} catch (java.security.NoSuchAlgorithmException failure) {
			throw new IllegalStateException(failure);
		}

	}

	private UUID account(boolean pending, UUID structureID) {
		UUID userID = UUID.randomUUID();
		UUID employeeID = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO employees (employee_id, first_name, last_name, contract_hours, structure_id) VALUES (?, 'Fictitious', 'Activation', 35, ?)",
				employeeID, structureID);
		jdbc.update("""
				INSERT INTO user_accounts (user_id, employee_id, user_name, status, user_first_name, user_last_name,
				    user_email, user_phone_number)
				VALUES (?, ?, ?, ?, 'Fictitious', 'Activation', ?, '+12025550100')
				""", userID, employeeID, pending ? null : "fictitious_owner_" + userID,
				pending ? "PENDING_ACTIVATION" : "ACTIVE", userID + "@example.invalid");
		return userID;
	}

	private String body(Fixture fixture, String proof) {
		var body = JSON.createObjectNode().put("token", fixture.token());

		if (proof != null) {
			body.put("proof", proof).put("userName", fixture.userName()).put("password", PASSWORD);
		}

		return body.toString();
	}

	private HttpClient client() {
		return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
				.proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER).build();
	}

	private URI base() {
		return URI.create("http://127.0.0.1:" + port);
	}

	private String path(Fixture fixture, String suffix) {
		return "/api/invitations/" + fixture.invitationID() + suffix;
	}

	private HttpResponse<String> get(HttpClient client, String path) throws Exception {
		return client.send(HttpRequest.newBuilder(base().resolve(path)).timeout(Duration.ofSeconds(15)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private JsonNode csrf(HttpClient client) throws Exception {
		HttpResponse<String> response = get(client, "/api/auth/csrf");
		assertEquals(200, response.statusCode());
		return JSON.readTree(response.body());
	}

	private HttpResponse<String> post(HttpClient client, String path, String body, JsonNode csrf) throws Exception {
		return client.send(HttpRequest.newBuilder(base().resolve(path)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.header(csrf.get("headerName").asString(), csrf.get("token").asString())
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private record Fixture(UUID invitationID, UUID userID, String userName, String token) {
	}
}
