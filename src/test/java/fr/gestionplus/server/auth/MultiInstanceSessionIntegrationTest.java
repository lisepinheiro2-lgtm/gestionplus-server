package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer.OrderAnnotation;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;

import fr.gestionplus.server.account.PrivilegedAccountSnapshot;
import fr.gestionplus.server.account.SuperAdminSuccessionService.SuccessionPreview;
import fr.gestionplus.server.persistence.RedisSecuritySupport;
import fr.gestionplus.server.support.IsolatedClusterApplication;
import fr.gestionplus.server.support.SecurityTestRedis;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@TestMethodOrder(OrderAnnotation.class)
class MultiInstanceSessionIntegrationTest {

	private static final JsonMapper JSON = JsonMapper.builder().build();
	private static final String PASSWORD = "Fictitious-Cluster-Password-2026!";
	private static final String APPLICATION_PASSWORD = key();
	private static final String NAMESPACE = "cluster-test-" + UUID.randomUUID().toString().replace("-", "");
	private static final GenericContainer<?> REDIS = SecurityTestRedis.isolatedContainer();
	private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16-alpine")
			.withDatabaseName("gestionplus_cluster_test").withUsername("fictitious_migration").withPassword(key())
			.withCreateContainerCmdModifier(command -> command.getHostConfig().withPortBindings(
					new PortBinding(Ports.Binding.bindIpAndPort("127.0.0.1", 0), ExposedPort.tcp(5432))));
	private static final Properties SETTINGS = new Properties();
	private static Path directory;
	private static JdbcTemplate jdbc;
	private static Process first;
	private static Process second;
	private static URI firstUrl;
	private static URI secondUrl;
	private static LettuceConnectionFactory connection;
	private static StringRedisTemplate redis;

	@BeforeAll
	static void startCluster() throws Exception {
		directory = Files.createTempDirectory("gestionplus-cluster-tests-");
		System.out.println("Preuves des deux instances : " + directory);

		try {
			REDIS.start();
			POSTGRES.start();
			Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
					.locations("classpath:db/migration").load().migrate();
			jdbc = new JdbcTemplate(new DriverManagerDataSource(
					POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
			jdbc.execute("CREATE ROLE fictitious_cluster_application LOGIN PASSWORD '" + APPLICATION_PASSWORD + "'");
			jdbc.execute("GRANT USAGE ON SCHEMA public TO fictitious_cluster_application");
			jdbc.execute(
					"GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO fictitious_cluster_application");
			jdbc.execute("GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO fictitious_cluster_application");
			jdbc.execute("REVOKE UPDATE, DELETE ON audit_events FROM fictitious_cluster_application");
			SETTINGS.putAll(Map.of(
					"spring.datasource.url", POSTGRES.getJdbcUrl(),
					"spring.datasource.username", "fictitious_cluster_application",
					"spring.datasource.password", APPLICATION_PASSWORD,
					"spring.flyway.enabled", "false",
					"REDIS_HOST", REDIS.getHost(),
					"REDIS_PORT", REDIS.getMappedPort(6379).toString(),
					"REDIS_PASSWORD", SecurityTestRedis.password(),
					"GESTIONPLUS_CLUSTER_NAMESPACE", NAMESPACE,
					"GESTIONPLUS_RATE_LIMIT_KEY", SecurityTestRedis.rateKey()));
			SETTINGS.putAll(Map.of(
					"server.address", "127.0.0.1", "server.port", "0",
					"gestionplus.audit.fallback.key", key(),
					"gestionplus.audit.fallback.directory", directory.resolve("audit-fallback").toString(),
					"gestionplus.activation.delivery-key", key(),
					"gestionplus.activation.challenge-key", key(),
					"gestionplus.mfa.key", key(),
					"gestionplus.activation.expiry-enabled", "false",
					"gestionplus.activation.delivery-initial-delay", "86400000"));
			SETTINGS.putAll(Map.of(
					"spring.config.location", "classpath:/application.yaml",
					"logging.level.root", "WARN",
					"spring.main.banner-mode", "off",
					"gestionplus.sms.fictif", "false",
					"gestionplus.sms.free-test.enabled", "false",
					"cluster.test.control-directory", directory.toString()));
			first = startNode("first");
			firstUrl = ready("first", first);
			second = startNode("second");
			secondUrl = ready("second", second);
			RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(REDIS.getHost(),
					REDIS.getMappedPort(6379));
			configuration.setPassword(SecurityTestRedis.password());
			connection = new LettuceConnectionFactory(configuration);
			connection.afterPropertiesSet();
			connection.start();
			redis = new StringRedisTemplate(connection);
		} catch (Exception failure) {
			stopCluster();
			throw failure;
		}

	}

	@AfterAll
	static void stopCluster() throws Exception {
		stop(first);
		stop(second);

		if (connection != null) {
			connection.destroy();
		}

		if (REDIS.isRunning()) {
			REDIS.stop();
		}

		if (POSTGRES.isRunning()) {
			POSTGRES.stop();
		}

	}

	@Test
	@Order(1)
	void authenticatesOnOneJvmAndReadsTheSameIdentityOnTheOther() throws Exception {
		assertNotEquals(first.pid(), second.pid());
		var account = account();

		try (var client = client()) {
			assertEquals(200, login(client, firstUrl, secondUrl, account.name(), PASSWORD).statusCode());
			assertEquals(account.name(),
					JSON.readTree(get(client, firstUrl, "/api/auth/me").body()).get("userName").asString());
			assertEquals(account.name(),
					JSON.readTree(get(client, secondUrl, "/api/auth/me").body()).get("userName").asString());
		}

	}

	@Test
	@Order(2)
	void sharesCsrfAndLogoutAcrossBothInstances() throws Exception {
		var account = account();

		try (var client = client()) {
			assertEquals(200, login(client, firstUrl, secondUrl, account.name(), PASSWORD).statusCode());
			JsonNode csrf = csrf(client, firstUrl);
			assertEquals(204, post(client, secondUrl, "/logout", "", csrf).statusCode());
			assertEquals(401, get(client, firstUrl, "/api/auth/me").statusCode());
			assertEquals(401, get(client, secondUrl, "/api/auth/me").statusCode());
		}

	}

	@Test
	@Order(31)
	void onlyOneJvmCanFinalizeTheSameSuccessionAndBothSessionsAreRevoked() throws Exception {
		var outgoing = account();
		var successor = account();
		CookieManager outgoingCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
		CookieManager successorCookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

		try (var outgoingClient = client(outgoingCookies); var successorClient = client(successorCookies)) {
			assertEquals(200, login(outgoingClient, firstUrl, firstUrl, outgoing.name(), PASSWORD).statusCode());
			assertEquals(200, login(successorClient, secondUrl, secondUrl, successor.name(), PASSWORD).statusCode());
			String outgoingSession = sessionID(outgoingCookies);
			String successorSession = sessionID(successorCookies);
			redis.opsForHash().put(activityKey(outgoingSession), "mfa", "1");
			redis.opsForHash().put(activityKey(successorSession), "mfa", "1");
			UUID tenant = UUID.randomUUID();
			UUID offer = UUID.randomUUID();
			jdbc.update(
					"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, ?, ?)",
					tenant, "Fictitious succession organization", outgoing.id());

			for (UUID participant : List.of(outgoing.id(), successor.id())) {
				jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", participant, tenant);
			}

			String digest = new SuccessionPreview(tenant, List.of(),
					new PrivilegedAccountSnapshot(outgoing.id(), UserAccountStatus.ACTIVE, 0, List.of()),
					new PrivilegedAccountSnapshot(successor.id(), UserAccountStatus.ACTIVE, 0, List.of()),
					List.of(), true).confirmationDigest();
			OffsetDateTime now = new RedisSecuritySupport(redis, NAMESPACE, SecurityTestRedis.rateKey())
					.now().atOffset(ZoneOffset.UTC);
			jdbc.update("""
					INSERT INTO succession_offers
					    (offer_id, tenant_id, outgoing_user_id, successor_user_id, admin_roles_to_transfer,
					     preview_digest, status, created_at, expires_at)
					VALUES (?, ?, ?, ?, '{}'::uuid[], ?, 'ACCEPTED', ?, ?)
					""", offer, tenant, outgoing.id(), successor.id(), digest, now, now.plusHours(24));
			Map<UUID, String> sessions = Map.of(outgoing.id(), outgoingSession, successor.id(), successorSession);

			for (var session : sessions.entrySet()) {
				jdbc.update(
						"""
								INSERT INTO succession_confirmations
								    (offer_id, user_id, session_version, session_id_hash, preview_digest, confirmed_at, expires_at)
								VALUES (?, ?, 0, ?, ?, ?, ?)
								""",
						offer, session.getKey(), SessionIDHasher.hash(session.getValue()), digest, now,
						now.plusMinutes(5));
			}

			var firstResult = successorClient.sendAsync(finalization(firstUrl, offer),
					HttpResponse.BodyHandlers.discarding());
			var secondResult = successorClient.sendAsync(finalization(secondUrl, offer),
					HttpResponse.BodyHandlers.discarding());
			assertEquals(List.of(204, 403), List.of(firstResult.get(25, TimeUnit.SECONDS).statusCode(),
					secondResult.get(25, TimeUnit.SECONDS).statusCode()).stream().sorted().toList());
			assertEquals(successor.id(), jdbc.queryForObject(
					"SELECT super_admin_user_id FROM tenant_organizations WHERE tenant_id = ?", UUID.class, tenant));
			assertEquals("COMPLETED", jdbc.queryForObject(
					"SELECT status FROM succession_offers WHERE offer_id = ?", String.class, offer));
			assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM succession_confirmations WHERE offer_id = ?",
					Integer.class, offer));
			assertEquals(false, redis.hasKey(activityKey(outgoingSession)));
			assertEquals(false, redis.hasKey(activityKey(successorSession)));
			assertEquals(401, get(outgoingClient, secondUrl, "/api/auth/me").statusCode());
			assertEquals(401, get(successorClient, firstUrl, "/api/auth/me").statusCode());
		}

	}

	private static HttpRequest finalization(URI base, UUID offerID) {
		return HttpRequest.newBuilder(base.resolve("/api/auth/me")).timeout(Duration.ofSeconds(22))
				.header("X-Cluster-Test-Finalize-Offer", offerID.toString()).GET().build();
	}

	@Test
	@Order(3)
	void enforcesTheAuthoritativeSecurityVersionOnBothInstances() throws Exception {
		var account = account();

		try (var client = client()) {
			assertEquals(200, login(client, firstUrl, firstUrl, account.name(), PASSWORD).statusCode());
			jdbc.update("UPDATE user_accounts SET session_version = session_version + 1 WHERE user_id = ?",
					account.id());
			assertEquals(401, get(client, secondUrl, "/api/auth/me").statusCode());
			assertEquals(401, get(client, firstUrl, "/api/auth/me").statusCode());
		}

	}

	@Test
	@Order(4)
	void doesNotExtendActivityWhenPollingSessionStatus() throws Exception {
		var account = account();

		try (var client = client()) {
			assertEquals(200, login(client, firstUrl, firstUrl, account.name(), PASSWORD).statusCode());
			JsonNode before = JSON.readTree(get(client, firstUrl, "/api/auth/session").body());
			csrf(client, secondUrl);
			JsonNode after = JSON.readTree(get(client, secondUrl, "/api/auth/session").body());
			assertEquals(before.get("expiresAt"), after.get("expiresAt"));
			assertEquals(before.get("warnAt"), after.get("warnAt"));
		}

	}

	@Test
	@Order(5)
	void expiresTheSessionOnBothInstancesAtTheAbsoluteDeadline() throws Exception {
		var account = account();
		CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);

		try (var client = client(cookies)) {
			assertEquals(200, login(client, firstUrl, firstUrl, account.name(), PASSWORD).statusCode());
			String sessionID = sessionID(cookies);
			long old = System.currentTimeMillis() - Duration.ofHours(8).toMillis() - 1000;
			redis.opsForHash().put(activityKey(sessionID), "started", Long.toString(old));
			assertEquals(401, get(client, secondUrl, "/api/auth/me").statusCode());
			assertEquals(401, get(client, firstUrl, "/api/auth/me").statusCode());
		}

	}

	@Test
	@Order(6)
	void neverRestoresASessionWhenAnInflightRequestCompletesAfterLogout() throws Exception {
		var account = account();

		try (var client = client()) {
			assertEquals(200, login(client, firstUrl, firstUrl, account.name(), PASSWORD).statusCode());
			var delayed = client.sendAsync(HttpRequest.newBuilder(secondUrl.resolve("/api/auth/me"))
					.header("X-Cluster-Test-Delay", "true").timeout(Duration.ofSeconds(20)).GET().build(),
					HttpResponse.BodyHandlers.ofString());
			awaitFile(directory.resolve("read-blocked.marker"), second);

			try {
				JsonNode csrf = csrf(client, firstUrl);
				assertEquals(204, post(client, firstUrl, "/logout", "", csrf).statusCode());
			} finally {
				Files.writeString(directory.resolve("release-read.marker"), "release");
			}

			assertEquals(200, delayed.get(20, TimeUnit.SECONDS).statusCode());
			assertEquals(401, get(client, firstUrl, "/api/auth/me").statusCode());
			assertEquals(401, get(client, secondUrl, "/api/auth/me").statusCode());
		}

	}

	@Test
	@Order(7)
	void retainsAnAuthenticatedSessionWhenOneJvmRestarts() throws Exception {
		var account = account();

		try (var client = client()) {
			assertEquals(200, login(client, firstUrl, secondUrl, account.name(), PASSWORD).statusCode());
			long previousPid = first.pid();
			stop(first);
			first = startNode("first");
			firstUrl = ready("first", first);
			assertNotEquals(previousPid, first.pid());
			assertEquals(200, get(client, firstUrl, "/api/auth/me").statusCode());
			assertEquals(200, get(client, secondUrl, "/api/auth/me").statusCode());
		}

	}

	@Test
	@Order(8)
	void rejectsInvalidCredentialsAndMissingCsrfWithoutAuthenticatingTheSession() throws Exception {
		var account = account();

		try (var client = client()) {
			assertEquals(401,
					login(client, firstUrl, secondUrl, account.name(), "fictitious-wrong-password").statusCode());
			assertEquals(401, get(client, firstUrl, "/api/auth/me").statusCode());
			HttpRequest noCsrf = HttpRequest.newBuilder(secondUrl.resolve("/login"))
					.header("Content-Type", "application/x-www-form-urlencoded")
					.POST(HttpRequest.BodyPublishers.ofString("username=fictitious&password=fictitious")).build();
			assertEquals(403, client.send(noCsrf, HttpResponse.BodyHandlers.ofString()).statusCode());
		}

	}

	@Test
	@Order(10)
	void recoversAnUnconfirmedSendAfterBothJvmInstancesWereStopped() throws Exception {
		var account = account();
		UUID challengeID = UUID.randomUUID();
		stop(first);
		stop(second);
		jdbc.update("""
				INSERT INTO account_activation_identity_challenges
				    (challenge_id, user_id, invitation_id, phone_number, code_digest, created_at, expires_at,
				     send_recovery_at)
				VALUES (?, ?, ?, '+12025550100', ?, CURRENT_TIMESTAMP - INTERVAL '3 minutes',
				    CURRENT_TIMESTAMP + INTERVAL '5 minutes', CURRENT_TIMESTAMP - INTERVAL '1 minute')
				""", challengeID, account.id(), UUID.randomUUID(), "a".repeat(64));
		first = startNode("first");
		firstUrl = ready("first", first);
		assertEquals(1, jdbc.queryForObject("""
				SELECT COUNT(*) FROM account_activation_identity_challenges
				WHERE challenge_id = ? AND revoked_at IS NOT NULL AND send_confirmed_at IS NULL
				""", Integer.class, challengeID));
		second = startNode("second");
		secondUrl = ready("second", second);
	}

	@Test
	@Order(20)
	void sharesThePublicActivationQuotaBetweenRoutesAndJvmInstancesIncludingEncodedPaths() throws Exception {
		UUID invitationID = UUID.randomUUID();

		try (var client = client()) {
			JsonNode token = csrf(client, firstUrl);

			for (int index = 0; index < 30; index++) {
				URI server = index % 2 == 0 ? firstUrl : secondUrl;
				String prefix = index % 3 == 0 ? "/api/%69nvitations/" : "/api/invitations/";
				String suffix = index % 2 == 0 ? "/sms-challenge" : "/activation";
				var response = publicActivationPost(client, server, prefix + invitationID + suffix, token);
				assertEquals(403, response.statusCode());
				assertEquals("ACTIVATION_DENIED", JSON.readTree(response.body()).get("code").asString());
			}

			for (URI server : new URI[] { firstUrl, secondUrl }) {

				for (String suffix : new String[] { "/sms-challenge", "/activation" }) {
					var response = publicActivationPost(client, server, "/api/%69nvitations/" + invitationID + suffix,
							token);
					assertEquals(429, response.statusCode());
					assertEquals("TOO_MANY_REQUESTS", JSON.readTree(response.body()).get("code").asString());
					long retryAfter = Long.parseLong(response.headers().firstValue("Retry-After").orElseThrow());
					assertTrue(retryAfter >= 1 && retryAfter <= 900);
				}

			}

			assertEquals(401, get(client, secondUrl, "/api/auth/me").statusCode());
		}

	}

	@Test
	@Order(30)
	void refusesRequestsWhenRedisIsUnavailableInsteadOfUsingLocalSessions() throws Exception {
		var account = account();

		try (var client = client()) {
			assertEquals(200, login(client, firstUrl, firstUrl, account.name(), PASSWORD).statusCode());
			JsonNode token = csrf(client, firstUrl);
			DockerClientFactory.instance().client().pauseContainerCmd(REDIS.getContainerId()).exec();

			try {
				assertEquals(503, get(client, firstUrl, "/api/auth/me").statusCode());
				assertEquals(503, get(client, secondUrl, "/api/auth/me").statusCode());

				try (var anonymous = client()) {
					assertEquals(503, publicActivationPost(anonymous, firstUrl,
							"/api/invitations/" + UUID.randomUUID() + "/sms-challenge", token).statusCode());
					assertEquals(503, publicActivationPost(anonymous, secondUrl,
							"/api/invitations/" + UUID.randomUUID() + "/activation", token).statusCode());
				}

			} finally {
				DockerClientFactory.instance().client().unpauseContainerCmd(REDIS.getContainerId()).exec();
			}

		}

	}

	@Test
	@Order(9)
	void sharesMfaAssuranceAndRejectsAReplayedTotpOnAnotherInstance() throws Exception {
		var account = account();
		byte[] secret = new byte[MfaSecretCipher.SECRET_BYTES];
		new SecureRandom().nextBytes(secret);
		byte[] encrypted = new MfaSecretCipher(SETTINGS.getProperty("gestionplus.mfa.key"))
				.encrypt(account.id(), null, secret);
		jdbc.update("INSERT INTO account_mfa_state (user_id, encrypted_secret, last_accepted_step) VALUES (?, ?, -1)",
				account.id(), encrypted);
		String code = TotpCodes.atStep(secret, java.time.Instant.now().getEpochSecond() / 30);

		try (var authenticated = client(); var replay = client()) {
			JsonNode token = csrf(authenticated, firstUrl);
			String form = "username=" + encode(account.name()) + "&password=" + encode(PASSWORD) + "&totp=" + code;
			assertEquals(200, post(authenticated, secondUrl, "/login", form, token).statusCode());
			assertEquals(200, get(authenticated, firstUrl, "/api/auth/me").statusCode());
			assertEquals(200, get(authenticated, secondUrl, "/api/auth/me").statusCode());
			assertEquals(401, post(replay, firstUrl, "/login", form, csrf(replay, secondUrl)).statusCode());
		}

	}

	private static Process startNode(String node) throws Exception {
		Files.deleteIfExists(directory.resolve(node + "-ready.txt"));
		Properties properties = new Properties();
		properties.putAll(SETTINGS);
		properties.setProperty("cluster.test.node", node);
		Path audit = Files.createDirectories(directory.resolve(node + "-audit"));

		if (Files.getFileAttributeView(audit, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
			Files.setPosixFilePermissions(audit, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
		}

		properties.setProperty("gestionplus.audit.fallback.directory", audit.toString());
		Path settings = directory.resolve(node + ".properties");

		try (var writer = Files.newBufferedWriter(settings, StandardCharsets.UTF_8)) {
			properties.store(writer, null);
		}

		Path arguments = directory.resolve(node + ".args");
		Path temporary = Files.createDirectories(directory.resolve(node + "-temporary"));
		Files.writeString(arguments, "-Xmx384m\n\"-Djava.io.tmpdir=" + temporary.toString().replace("\\", "/")
				+ "\"\n-classpath\n\""
				+ System.getProperty("java.class.path").replace("\\", "/") + "\"\n"
				+ IsolatedClusterApplication.class.getName() + "\n\""
				+ settings.toString().replace("\\", "/") + "\"\n", StandardCharsets.UTF_8);
		ProcessBuilder builder = new ProcessBuilder(
				Path.of(System.getProperty("java.home"), "bin",
						System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString(),
				"@" + arguments);
		String systemRoot = builder.environment().get("SystemRoot");
		builder.environment().clear();
		builder.environment().put("TEMP", temporary.toString());
		builder.environment().put("TMP", temporary.toString());

		if (systemRoot != null) {
			builder.environment().put("SystemRoot", systemRoot);
		}

		Process process = builder.directory(directory.toFile()).redirectErrorStream(true)
				.redirectOutput(directory.resolve(node + ".log").toFile()).start();
		Files.writeString(directory.resolve("owned-processes.txt"), process.pid() + System.lineSeparator(),
				java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		return process;
	}

	private static URI ready(String node, Process process) throws Exception {
		Path ready = directory.resolve(node + "-ready.txt");
		awaitFile(ready, process);
		return URI.create("http://127.0.0.1:" + Files.readString(ready).strip());
	}

	private static void awaitFile(Path file, Process process) throws Exception {
		long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();

		while (!Files.exists(file)) {

			if (!process.isAlive() || System.nanoTime() >= deadline) {
				throw new IllegalStateException("L’instance isolée n’est pas prête. Journaux : " + directory);
			}

			Thread.sleep(50);
		}

	}

	private static void stop(Process process) throws Exception {

		if (process == null || !process.isAlive()) {
			return;
		}

		process.destroy();

		if (!process.waitFor(10, TimeUnit.SECONDS)) {
			process.destroyForcibly();
			assertTrue(process.waitFor(10, TimeUnit.SECONDS));
		}

	}

	private static HttpClient client() {
		return client(new CookieManager(null, CookiePolicy.ACCEPT_ALL));
	}

	private static HttpClient client(CookieManager cookies) {
		return HttpClient.newBuilder().cookieHandler(cookies).proxy(HttpClient.Builder.NO_PROXY)
				.followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(3)).build();
	}

	private static HttpResponse<String> get(HttpClient client, URI base, String path) throws Exception {
		return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(12)).GET().build(),
				HttpResponse.BodyHandlers.ofString());
	}

	private static JsonNode csrf(HttpClient client, URI base) throws Exception {
		var response = get(client, base, "/api/auth/csrf");
		assertEquals(200, response.statusCode());
		return JSON.readTree(response.body());
	}

	private static HttpResponse<String> login(HttpClient client, URI csrfServer, URI loginServer, String name,
			String password) throws Exception {
		JsonNode token = csrf(client, csrfServer);
		return post(client, loginServer, "/login", "username=" + encode(name) + "&password=" + encode(password), token);
	}

	private static HttpResponse<String> post(HttpClient client, URI base, String path, String body, JsonNode csrf)
			throws Exception {
		return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(12))
				.header("Content-Type", "application/x-www-form-urlencoded")
				.header(csrf.get("headerName").asString(), csrf.get("token").asString())
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	@Test
	@Order(11)
	void enrollsAcrossBothJvmsAndReturnsToNormalLoginAfterConfirmation() throws Exception {
		Account account = account();
		String proof = enrollmentProof(account);

		try (var client = enrollmentClient(); var replay = enrollmentClient()) {
			assertEquals(204, jsonPost(client, secondUrl, "/api/auth/mfa/enrollment/session",
					JSON.createObjectNode().put("proof", proof).toString(), csrf(client, firstUrl)).statusCode());
			assertEquals(403, jsonPost(replay, firstUrl, "/api/auth/mfa/enrollment/session",
					JSON.createObjectNode().put("proof", proof).toString(), csrf(replay, secondUrl)).statusCode());
			assertEquals(403, get(client, firstUrl, "/api/auth/me").statusCode());
			assertEquals(403, get(client, secondUrl, "/api/structures").statusCode());
			JsonNode enrollment = JSON.readTree(jsonPost(client, firstUrl, "/api/auth/mfa/enrollment", "{}",
					csrf(client, secondUrl)).body());
			assertTrue(enrollment.has("setupKey"));
			JsonNode repeated = JSON.readTree(jsonPost(client, secondUrl, "/api/auth/mfa/enrollment", "{}",
					csrf(client, firstUrl)).body());
			assertEquals(enrollment.get("enrollmentID"), repeated.get("enrollmentID"));
			assertEquals(enrollment.get("setupKey"), repeated.get("setupKey"));
			UUID enrollmentID = UUID.fromString(enrollment.get("enrollmentID").asString());
			byte[] secret = enrollmentSecret(account, enrollmentID);

			try {
				long step = Instant.now().getEpochSecond() / 30;
				String code = TotpCodes.atStep(secret, step);
				var response = jsonPost(client, secondUrl, "/api/auth/mfa/enrollment/complete",
						JSON.createObjectNode().put("enrollmentID", enrollmentID.toString()).put("code", code)
								.toString(),
						csrf(client, firstUrl));
				assertEquals(200, response.statusCode());
				assertEquals(10, JSON.readTree(response.body()).get("codes").size());
				assertEquals(401, get(client, firstUrl, "/api/auth/me").statusCode());
				assertEquals(401, get(client, secondUrl, "/api/auth/me").statusCode());
				String form = "username=" + encode(account.name()) + "&password=" + encode(PASSWORD) + "&totp=" + code;
				assertEquals(401, post(client, firstUrl, "/login", form, csrf(client, secondUrl)).statusCode());
				Thread.sleep(500);
				long deadline = System.nanoTime() + Duration.ofSeconds(35).toNanos();

				while (Instant.now().getEpochSecond() / 30 <= step && System.nanoTime() < deadline) {
					Thread.sleep(50);
				}

				long nextStep = Instant.now().getEpochSecond() / 30;
				assertTrue(nextStep > step);
				String nextForm = "username=" + encode(account.name()) + "&password=" + encode(PASSWORD)
						+ "&totp=" + TotpCodes.atStep(secret, nextStep);
				assertEquals(200, post(client, secondUrl, "/login", nextForm, csrf(client, firstUrl)).statusCode());
				assertEquals(200, get(client, firstUrl, "/api/auth/me").statusCode());
			} finally {
				java.util.Arrays.fill(secret, (byte) 0);
			}

		}

	}

	@Test
	@Order(12)
	void sharesTheTotpSubmissionLimitAcrossJvmsDespiteDatabaseRollbacksAndRepeatedBegin() throws Exception {
		Account account = account();
		String proof = enrollmentProof(account);

		try (var client = enrollmentClient()) {
			assertEquals(204, jsonPost(client, firstUrl, "/api/auth/mfa/enrollment/session",
					JSON.createObjectNode().put("proof", proof).toString(), csrf(client, secondUrl)).statusCode());
			JsonNode enrollment = JSON.readTree(jsonPost(client, secondUrl, "/api/auth/mfa/enrollment", "{}",
					csrf(client, firstUrl)).body());
			UUID enrollmentID = UUID.fromString(enrollment.get("enrollmentID").asString());
			byte[] secret = enrollmentSecret(account, enrollmentID);

			try {
				java.util.Set<String> possibleCodes = new java.util.HashSet<>();
				long currentStep = Instant.now().getEpochSecond() / 30;

				for (long step = currentStep - 1; step <= currentStep + 20; step++) {
					possibleCodes.add(TotpCodes.atStep(secret, step));
				}

				String wrong = "000000";

				while (possibleCodes.contains(wrong)) {
					wrong = String.format(java.util.Locale.ROOT, "%06d", Integer.parseInt(wrong) + 1);
				}

				String body = JSON.createObjectNode().put("enrollmentID", enrollmentID.toString()).put("code", wrong)
						.toString();

				for (int attempt = 0; attempt < 5; attempt++) {
					assertEquals(403, jsonPost(client, attempt % 2 == 0 ? firstUrl : secondUrl,
							"/api/auth/mfa/enrollment/complete", body, csrf(client, secondUrl)).statusCode());
				}

				assertEquals(200, jsonPost(client, secondUrl, "/api/auth/mfa/enrollment", "{}", csrf(client, firstUrl))
						.statusCode());
				var blocked = jsonPost(client, secondUrl, "/api/auth/mfa/enrollment/complete", body,
						csrf(client, firstUrl));
				assertEquals(429, blocked.statusCode());
				assertTrue(Long.parseLong(blocked.headers().firstValue("Retry-After").orElseThrow()) >= 1);
				assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM account_mfa_state WHERE user_id = ?",
						Integer.class, account.id()));
			} finally {
				java.util.Arrays.fill(secret, (byte) 0);
			}

		}

	}

	private static String enrollmentProof(Account account) throws Exception {
		UUID tenantID = UUID.randomUUID();
		jdbc.update(
				"INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id) VALUES (?, 'Fictitious enrollment', ?)",
				tenantID, account.id());
		jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", account.id(), tenantID);
		byte[] bytes = new byte[16];
		new SecureRandom().nextBytes(bytes);
		String proof = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
		String hash = HexFormat.of()
				.formatHex(MessageDigest.getInstance("SHA-256").digest(proof.getBytes(StandardCharsets.US_ASCII)));
		jdbc.update(
				"INSERT INTO account_mfa_enrollment_proofs (user_id, proof_hash, session_version, expires_at) VALUES (?, ?, 0, ?)",
				account.id(), hash, OffsetDateTime.ofInstant(Instant.now().plusSeconds(300), ZoneOffset.UTC));
		return proof;
	}

	private static byte[] enrollmentSecret(Account account, UUID enrollmentID) {
		byte[] encrypted = jdbc.queryForObject("SELECT encrypted_secret FROM account_mfa_enrollments WHERE user_id = ?",
				byte[].class, account.id());
		return new MfaSecretCipher(SETTINGS.getProperty("gestionplus.mfa.key")).decrypt(account.id(), enrollmentID,
				encrypted);
	}

	private static HttpClient enrollmentClient() throws Exception {
		return HttpClient.newBuilder().cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER))
				.proxy(HttpClient.Builder.NO_PROXY).followRedirects(HttpClient.Redirect.NEVER)
				.localAddress(InetAddress.getByName("127.0.0.2")).connectTimeout(Duration.ofSeconds(5)).build();
	}

	private static HttpResponse<String> jsonPost(HttpClient client, URI base, String path, String body, JsonNode csrf)
			throws Exception {
		return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(15))
				.header("Content-Type", "application/json")
				.header(csrf.get("headerName").asString(), csrf.get("token").asString())
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static String encode(String value) {
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}

	private static HttpResponse<String> publicActivationPost(HttpClient client, URI base, String path, JsonNode csrf)
			throws Exception {
		String body = JSON.createObjectNode().put("token", "a".repeat(43)).put("proof", "012345")
				.put("userName", "fictitious_activation").put("password", "Fictitious-Activation-Password-2026!")
				.toString();
		return client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(12))
				.header("Content-Type", "application/json")
				.header(csrf.get("headerName").asString(), csrf.get("token").asString())
				.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
	}

	private static Account account() {
		UUID userID = UUID.randomUUID();
		UUID employeeID = UUID.randomUUID();
		String name = "fictitious_cluster_" + userID;
		jdbc.update("INSERT INTO employees (employee_id, first_name, last_name, contract_hours) VALUES (?, ?, ?, ?)",
				employeeID, "Fictitious", "Cluster", 35);
		jdbc.update("""
				INSERT INTO user_accounts (user_id, employee_id, user_name, status,
				    user_last_name, user_first_name, user_email, user_phone_number, session_version)
				VALUES (?, ?, ?, 'ACTIVE', 'Cluster', 'Fictitious', ?, '+12025550100', 0)
				""", userID, employeeID, name, userID + "@example.invalid");
		jdbc.update("INSERT INTO user_credentials (user_id, password_hash) VALUES (?, ?)", userID,
				Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8().encode(PASSWORD));
		return new Account(userID, name);
	}

	private static String sessionID(CookieManager cookies) {
		String value = cookies.getCookieStore().getCookies().stream()
				.filter(cookie -> cookie.getName().equals("JSESSIONID"))
				.findFirst().orElseThrow().getValue();

		if (value.startsWith("\"") && value.endsWith("\"")) {
			value = value.substring(1, value.length() - 1);
		}

		try {
			return UUID.fromString(value).toString();
		} catch (IllegalArgumentException encoded) {
			return UUID.fromString(new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)).toString();
		}

	}

	private static String activityKey(String sessionID) {
		return "gestionplus:" + NAMESPACE + ":{session-activity}:" + sessionID;
	}

	private static String key() {
		byte[] bytes = new byte[32];
		new SecureRandom().nextBytes(bytes);
		return Base64.getEncoder().encodeToString(bytes);
	}

	private record Account(UUID id, String name) {
	}
}
