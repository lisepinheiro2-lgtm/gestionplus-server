import java.io.IOException;
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
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.PortBinding;
import com.github.dockerjava.api.model.Ports;
import fr.gestionplus.server.GestionplusServerApplication;
import fr.gestionplus.server.auth.LoginDelay;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.json.JsonMapper;

public final class LocalLoginPathBenchmark {

    private static final int WARMUP_PER_CASE = 25;
    private static final int ROUNDS = 3;
    private static final int SAMPLES_PER_CASE_PER_ROUND = 50;
    private static final String CORRECT_PASSWORD = "Fictitious-benchmark-" + randomKey();
    private static final String WRONG_PASSWORD = CORRECT_PASSWORD + "?";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("Expected one argument: a new results directory.");
        }
        Path directory = Path.of(args[0]).toAbsolutePath().normalize();
        if (Files.exists(directory.resolve("samples.csv")) || Files.exists(directory.resolve("summary.json"))) {
            throw new IllegalArgumentException("The results directory already contains a benchmark run.");
        }
        Files.createDirectories(directory.resolve("audit-journal"));
        String runId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String databaseName = "gestionplus_benchmark_" + runId;
        String containerName = "gestionplus-login-benchmark-" + runId;
        List<Sample> samples = new ArrayList<>();
        Map<String, Object> environment = new LinkedHashMap<>();
        environment.put("startedAtUtc", Instant.now().toString());
        environment.put("javaVersion", System.getProperty("java.version"));
        environment.put("availableProcessors", Runtime.getRuntime().availableProcessors());
        environment.put("maxHeapMiB", Runtime.getRuntime().maxMemory() / (1024L * 1024L));
        environment.put("databaseName", databaseName);
        environment.put("containerName", containerName);
        environment.put("transport", "HTTP/1.1 over IPv4 loopback; no TLS");
        environment.put("cases", Arrays.stream(LoginCase.values()).map(LoginCase::label).toList());
        environment.put("warmupPerCase", WARMUP_PER_CASE);
        environment.put("rounds", ROUNDS);
        environment.put("samplesPerCasePerRound", SAMPLES_PER_CASE_PER_ROUND);
        environment.put("limiter", "Production limiter enabled; one fresh username and source loopback IP per attempt");

        try (PostgreSQLContainer database = new PostgreSQLContainer("postgres:16-alpine")
                .withDatabaseName(databaseName)
                .withUsername("benchmark")
                .withPassword(randomKey())
                .withReuse(false)
                .withImagePullPolicy(image -> false)
                .withLabel("gestionplus.benchmark", runId)
                .withCreateContainerCmdModifier(command -> {
                    command.withName(containerName);
                    command.getHostConfig().withPortBindings(new PortBinding(
                            Ports.Binding.bindIpAndPort("127.0.0.1", 0), new ExposedPort(5432)));
                })) {

            System.out.println("Starting a new disposable PostgreSQL container.");
            database.start();
            environment.put("containerId", database.getContainerId());

            Map<String, Object> isolation = isolatedProperties(database, directory);
            StandardEnvironment springEnvironment = new StandardEnvironment();
            springEnvironment.setActiveProfiles("benchmark");
            springEnvironment.getPropertySources().addFirst(
                    new MapPropertySource("benchmarkIsolation", isolation));

            SpringApplication application = new SpringApplication(GestionplusServerApplication.class);
            application.setEnvironment(springEnvironment);
            application.addInitializers(context -> {
                context.getEnvironment().setActiveProfiles("benchmark");
                context.getEnvironment().getPropertySources().addFirst(
                        new MapPropertySource("benchmarkIsolationFinal", isolation));
            });

            System.out.println("Starting the isolated HTTP server and migrating only the disposable database.");

            try (ConfigurableApplicationContext context = application.run()) {
                int port = ((WebServerApplicationContext) context).getWebServer().getPort();
                JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
                String actualDatabase = jdbc.queryForObject("SELECT current_database()", String.class);
                if (!databaseName.equals(actualDatabase)) {
                    throw new IllegalStateException("Database isolation check failed.");
                }

                environment.put("httpPort", port);
                environment.put("postgresqlVersion", jdbc.queryForObject("SHOW server_version", String.class));
                environment.put("appliedMigrations", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM flyway_schema_history WHERE success", Integer.class));
                environment.put("virtualThreadsEnabled",
                        context.getEnvironment().getProperty("spring.threads.virtual.enabled", Boolean.class));
                environment.put("maximumPoolSize",
                        context.getEnvironment().getProperty("spring.datasource.hikari.maximum-pool-size"));
                environment.put("loginDelayImplementation", context.getBean(LoginDelay.class).getClass().getName());
                environment.put("loginDelaySettings", "Unchanged project implementation; see the source manifest");
                Files.writeString(directory.resolve("run-environment.json"), JSON.writeValueAsString(environment));

                PasswordEncoder encoder = context.getBean(PasswordEncoder.class);
                String encodedPassword = encoder.encode(CORRECT_PASSWORD);
                environment.put("argon2Parameters", encodedPassword.split("\\$")[3]);
                TransactionTemplate transaction = new TransactionTemplate(
                        context.getBean(PlatformTransactionManager.class));
                transaction.executeWithoutResult(status -> seed(jdbc, encodedPassword));

                URI base = URI.create("http://127.0.0.1:" + port);
                int attempt = 0;
                System.out.println("HTTP warm-up: 25 attempts per case.");

                for (int index = 0; index < WARMUP_PER_CASE; index++) {
                    for (LoginCase loginCase : orderedCases(index)) {
                        samples.add(attempt(base, jdbc, loginCase, index, attempt++, 0));
                    }
                }

                writeSamples(directory, samples);

                for (int round = 1; round <= ROUNDS; round++) {
                    System.out.printf("Measured round %d/%d: 50 attempts per case.%n", round, ROUNDS);
                    for (int index = 0; index < SAMPLES_PER_CASE_PER_ROUND; index++) {
                        int fixtureIndex = WARMUP_PER_CASE
                                + (round - 1) * SAMPLES_PER_CASE_PER_ROUND + index;
                        for (LoginCase loginCase : orderedCases(index + round)) {
                            samples.add(attempt(base, jdbc, loginCase, fixtureIndex, attempt++, round));
                        }
                    }
                    writeSamples(directory, samples);
                    System.out.printf("Measured round %d completed.%n", round);
                    System.out.flush();
                }

                int limited = jdbc.queryForObject("""
                        SELECT COUNT(*) FROM audit_events
                        WHERE action = 'AUTH_LOGIN' AND metadata ->> 'reason' = 'RATE_LIMITED'
                        """, Integer.class);
                if (limited != 0) {
                    throw new IllegalStateException("Rate-limited samples invalidate the baseline.");
                }
                environment.put("rateLimitedSamples", limited);
                environment.put("auditLoginEvents", jdbc.queryForObject(
                        "SELECT COUNT(*) FROM audit_events WHERE action = 'AUTH_LOGIN'", Integer.class));
                environment.put("completedAtUtc", Instant.now().toString());
                environment.put("measuredSamples", samples.stream().filter(sample -> sample.round() > 0).count());
                Map<String, Object> summary = new LinkedHashMap<>();
                summary.put("environment", environment);
                for (LoginCase loginCase : LoginCase.values()) {
                    List<Sample> selected = samples.stream()
                            .filter(sample -> sample.round() > 0 && sample.loginCase() == loginCase).toList();
                    Map<String, Object> timing = new LinkedHashMap<>();
                    timing.put("count", selected.size());
                    timing.put("csrf", summarize(selected.stream().mapToLong(Sample::csrfNanos).toArray()));
                    timing.put("postLogin", summarize(selected.stream().mapToLong(Sample::loginNanos).toArray()));
                    timing.put("csrfPlusLogin", summarize(selected.stream().mapToLong(Sample::totalNanos).toArray()));
                    summary.put(loginCase.label(), timing);
                }
                Files.writeString(directory.resolve("summary.json"), JSON.writeValueAsString(summary));
                Files.writeString(directory.resolve("run-environment.json"), JSON.writeValueAsString(environment));
                System.out.println(JSON.writeValueAsString(summary));
            }

            System.out.println("HTTP server stopped; removing the disposable PostgreSQL container.");
        } finally {
            writeSamples(directory, samples);
        }

        Files.writeString(directory.resolve("completed.txt"), "Completed; owned server and container closed.\n");
    }

    private static Map<String, Object> isolatedProperties(PostgreSQLContainer database, Path directory) {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("spring.config.location", "optional:classpath:/");
        properties.put("spring.config.additional-location", "");
        properties.put("spring.config.import", "");
        properties.put("spring.profiles.active", "benchmark");
        properties.put("spring.profiles.default", "benchmark");
        properties.put("spring.profiles.include", "");
        properties.put("spring.main.banner-mode", "off");
        properties.put("spring.main.lazy-initialization", false);
        properties.put("spring.application.name", "gestionplus-local-login-benchmark");
        properties.put("server.address", "127.0.0.1");
        properties.put("server.port", 0);
        properties.put("server.ssl.enabled", false);
        properties.put("server.servlet.session.cookie.secure", false);
        properties.put("gestionplus.http.require-https", false);
        properties.put("spring.threads.virtual.enabled", true);
        properties.put("spring.datasource.type", "com.zaxxer.hikari.HikariDataSource");
        properties.put("spring.datasource.url", database.getJdbcUrl());
        properties.put("spring.datasource.username", database.getUsername());
        properties.put("spring.datasource.password", database.getPassword());
        properties.put("spring.datasource.driver-class-name", "org.postgresql.Driver");
        properties.put("spring.datasource.hikari.jdbc-url", database.getJdbcUrl());
        properties.put("spring.datasource.hikari.username", database.getUsername());
        properties.put("spring.datasource.hikari.password", database.getPassword());
        properties.put("spring.datasource.hikari.driver-class-name", "org.postgresql.Driver");
        properties.put("spring.datasource.hikari.maximum-pool-size", 10);
        properties.put("spring.datasource.hikari.minimum-idle", 10);
        properties.put("spring.flyway.enabled", true);
        properties.put("spring.flyway.url", database.getJdbcUrl());
        properties.put("spring.flyway.user", database.getUsername());
        properties.put("spring.flyway.password", database.getPassword());
        properties.put("spring.flyway.driver-class-name", "org.postgresql.Driver");
        properties.put("spring.flyway.locations", "classpath:db/migration");
        properties.put("spring.flyway.schemas", "public");
        properties.put("spring.flyway.default-schema", "public");
        properties.put("spring.flyway.clean-disabled", true);
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.jpa.open-in-view", false);
        properties.put("spring.jpa.properties.hibernate.default_schema", "public");
        properties.put("spring.mail.host", "false");
        properties.put("gestionplus.notifications.from", "false");
        properties.put("gestionplus.activation.link", "");
        properties.put("gestionplus.activation.expiry-enabled", false);
        properties.put("gestionplus.activation.delivery-initial-delay", "86400000");
        properties.put("gestionplus.activation.delivery-key", randomKey());
        properties.put("gestionplus.audit.fallback.key", randomKey());
        properties.put("gestionplus.audit.fallback.directory", directory.resolve("audit-journal").toString());
        properties.put("gestionplus.mfa.key", randomKey());
        properties.put("logging.level.root", "WARN");
        return properties;
    }

    private static void seed(JdbcTemplate jdbc, String passwordHash) {
        UUID bootstrapEmployee = UUID.randomUUID();
        UUID bootstrapAccount = UUID.randomUUID();
        UUID tenant = UUID.randomUUID();
        UUID structure = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO employees (employee_id, first_name, last_name, contract_hours, email, phone_number)
                VALUES (?, 'Fictitious', 'Benchmark Bootstrap', 35,
                    'fictitious-benchmark-bootstrap@example.invalid', '0000000000')
                """, bootstrapEmployee);
        jdbc.update("""
                INSERT INTO user_accounts (user_id, employee_id, user_name, status, user_last_name,
                    user_first_name, user_email, user_phone_number)
                VALUES (?, ?, 'fictitious_benchmark_bootstrap', 'ACTIVE', 'Benchmark Bootstrap', 'Fictitious',
                    'fictitious-benchmark-bootstrap@example.invalid', '0000000000')
                """, bootstrapAccount, bootstrapEmployee);
        jdbc.update("""
                INSERT INTO tenant_organizations (tenant_id, tenant_name, super_admin_user_id)
                VALUES (?, 'FICTITIOUS BENCHMARK TENANT - DISPOSABLE', ?)
                """, tenant, bootstrapAccount);
        jdbc.update("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", bootstrapAccount, tenant);
        jdbc.update("""
                INSERT INTO organizational_structures (structure_id, structure_name, tenant_id)
                VALUES (?, 'FICTITIOUS BENCHMARK STRUCTURE - DISPOSABLE', ?)
                """, structure, tenant);
        jdbc.update("UPDATE employees SET structure_id = ? WHERE employee_id = ?", structure, bootstrapEmployee);

        List<Object[]> employees = new ArrayList<>();
        List<Object[]> accounts = new ArrayList<>();
        List<Object[]> credentials = new ArrayList<>();
        List<Object[]> tenants = new ArrayList<>();
        List<Object[]> memberships = new ArrayList<>();
        List<Object[]> roles = new ArrayList<>();
        int fixturesPerCase = WARMUP_PER_CASE + ROUNDS * SAMPLES_PER_CASE_PER_ROUND;

        for (LoginCase loginCase : LoginCase.values()) {
            if (loginCase == LoginCase.UNKNOWN) {
                continue;
            }
            for (int index = 0; index < fixturesPerCase; index++) {
                UUID employee = UUID.randomUUID();
                UUID account = UUID.randomUUID();
                String email = username(loginCase, index) + "@example.invalid";
                employees.add(new Object[] { employee, email, structure });
                accounts.add(new Object[] { account, employee, username(loginCase, index),
                        loginCase == LoginCase.DISABLED ? "DISABLED" : "ACTIVE", email });
                credentials.add(new Object[] { account, passwordHash });
                tenants.add(new Object[] { account, tenant });
                if (loginCase != LoginCase.DISABLED) {
                    memberships.add(new Object[] { account, structure, tenant });
                    roles.add(new Object[] { account, structure });
                }
            }
        }

        jdbc.batchUpdate("""
                INSERT INTO employees (employee_id, first_name, last_name, contract_hours, email, phone_number, structure_id)
                VALUES (?, 'Fictitious', 'Benchmark', 35, ?, '0000000000', ?)
                """, employees);
        jdbc.batchUpdate("""
                INSERT INTO user_accounts (user_id, employee_id, user_name, status, user_last_name,
                    user_first_name, user_email, user_phone_number)
                VALUES (?, ?, ?, ?, 'Benchmark', 'Fictitious', ?, '0000000000')
                """, accounts);
        jdbc.batchUpdate("INSERT INTO user_credentials (user_id, password_hash) VALUES (?, ?)", credentials);
        jdbc.batchUpdate("INSERT INTO tenant_memberships (user_id, tenant_id) VALUES (?, ?)", tenants);
        jdbc.batchUpdate("INSERT INTO structure_memberships (user_id, structure_id, tenant_id) VALUES (?, ?, ?)",
                memberships);
        jdbc.batchUpdate("""
                INSERT INTO structure_membership_roles (user_id, structure_id, role) VALUES (?, ?, 'EMPLOYEE')
                """, roles);
    }

    private static Sample attempt(URI base, JdbcTemplate jdbc, LoginCase loginCase,
            int fixtureIndex, int attemptIndex, int round) throws Exception {
        byte[] address = { 127, 78, (byte) (attemptIndex / 250), (byte) (attemptIndex % 250 + 1) };
        InetAddress sourceAddress = InetAddress.getByAddress(address);
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        String candidate = loginCase == LoginCase.SUCCESS ? CORRECT_PASSWORD : WRONG_PASSWORD;
        String form = "username=" + URLEncoder.encode(username(loginCase, fixtureIndex), StandardCharsets.UTF_8)
                + "&password=" + URLEncoder.encode(candidate, StandardCharsets.UTF_8);

        try (HttpClient client = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .proxy(HttpClient.Builder.NO_PROXY)
                .cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10))
                .localAddress(sourceAddress)
                .build()) {
            HttpRequest csrfRequest = HttpRequest.newBuilder(base.resolve("/api/auth/csrf"))
                    .timeout(Duration.ofSeconds(15)).GET().build();
            long totalStarted = System.nanoTime();
            long csrfStarted = totalStarted;
            HttpResponse<String> csrfResponse = client.send(csrfRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long csrfNanos = System.nanoTime() - csrfStarted;
            if (csrfResponse.statusCode() != 200) {
                throw new IllegalStateException("CSRF request failed with status " + csrfResponse.statusCode());
            }
            String token = jsonField(csrfResponse.body(), "token");
            String headerName = jsonField(csrfResponse.body(), "headerName");
            HttpRequest loginRequest = HttpRequest.newBuilder(base.resolve("/login"))
                    .timeout(Duration.ofSeconds(15))
                    .header("Content-Type", "application/x-www-form-urlencoded;charset=UTF-8")
                    .header(headerName, token)
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            long loginStarted = System.nanoTime();
            HttpResponse<String> response = client.send(loginRequest,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long finished = System.nanoTime();
            if (response.statusCode() != loginCase.expectedStatus) {
                throw new IllegalStateException("Unexpected status for " + loginCase.label()
                        + ": " + response.statusCode());
            }
            List<Map<String, Object>> events = jdbc.queryForList("""
                    SELECT result, metadata ->> 'reason' AS reason, ip_address FROM audit_events
                    WHERE action = 'AUTH_LOGIN' AND ip_address = ?
                    """, sourceAddress.getHostAddress());
            if (events.size() != 1
                    || !Objects.equals(loginCase.expectedReason, events.getFirst().get("reason"))
                    || !Objects.equals(loginCase == LoginCase.SUCCESS ? "SUCCESS" : "FAILURE",
                            events.getFirst().get("result"))) {
                throw new IllegalStateException("Unexpected audit outcome for " + loginCase.label()
                        + "; no sample accepted.");
            }
            return new Sample(round, loginCase, fixtureIndex, response.statusCode(),
                    csrfNanos, finished - loginStarted, finished - totalStarted);
        }
    }

    private static String jsonField(String body, String field) {
        var matcher = Pattern.compile("\"" + field + "\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
        if (!matcher.find()) {
            throw new IllegalStateException("Required CSRF response field is absent.");
        }
        return matcher.group(1);
    }

    private static String username(LoginCase loginCase, int index) {
        return String.format(Locale.ROOT, "fictitious_benchmark_%s_%06d", loginCase.code, index);
    }

    private static List<LoginCase> orderedCases(int index) {
        List<LoginCase> cases = new ArrayList<>(List.of(LoginCase.values()));
        Collections.rotate(cases, index % cases.size());
        return cases;
    }

    private static Statistics summarize(long[] values) {
        long[] sorted = values.clone();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        double median = sorted.length % 2 == 0
                ? (sorted[middle - 1] + sorted[middle]) / 2.0 : sorted[middle];
        return new Statistics(sorted.length, sorted[0] / 1_000_000.0,
                median / 1_000_000.0,
                sorted[(int) Math.ceil(sorted.length * 0.95) - 1] / 1_000_000.0,
                sorted[sorted.length - 1] / 1_000_000.0,
                Arrays.stream(sorted).average().orElseThrow() / 1_000_000.0);
    }

    private static void writeSamples(Path directory, List<Sample> samples) throws IOException {
        StringBuilder csv = new StringBuilder(
                "round,case,fixture_index,http_status,csrf_nanos,post_login_nanos,total_nanos\n");
        for (Sample sample : samples) {
            csv.append(sample.round()).append(',').append(sample.loginCase().label()).append(',')
                    .append(sample.fixtureIndex()).append(',').append(sample.status()).append(',')
                    .append(sample.csrfNanos()).append(',').append(sample.loginNanos()).append(',')
                    .append(sample.totalNanos()).append('\n');
        }
        Files.writeString(directory.resolve("samples.csv"), csv);
    }

    private static String randomKey() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private enum LoginCase {
        SUCCESS("success", "ok_login", 200, null),
        WRONG("wrong_password", "bad_pass", 401, "INVALID_CREDENTIALS"),
        UNKNOWN("unknown_user", "notfound", 401, "UNKNOWN_ACCOUNT"),
        DISABLED("disabled_user", "disabled", 401, "DISABLED_ACCOUNT");

        private final String label;
        private final String code;
        private final int expectedStatus;
        private final String expectedReason;

        LoginCase(String label, String code, int expectedStatus, String expectedReason) {
            this.label = label;
            this.code = code;
            this.expectedStatus = expectedStatus;
            this.expectedReason = expectedReason;
        }

        String label() {
            return label;
        }
    }

    private record Sample(int round, LoginCase loginCase, int fixtureIndex, int status,
            long csrfNanos, long loginNanos, long totalNanos) {
    }

    private record Statistics(int count, double minMs, double medianMs, double p95Ms,
            double maxMs, double meanMs) {
    }
}

