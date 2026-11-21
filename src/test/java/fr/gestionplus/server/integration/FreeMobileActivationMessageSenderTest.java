package fr.gestionplus.server.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.gestionplus.server.account.ActivationMessageDeliveryException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class FreeMobileActivationMessageSenderTest {

	private static final String ACCOUNT_ID = "12345678";
	private static final String API_KEY = "fictitious-api-key";
	private static final String PHONE = "+12025550100";
	private static final String CODE = "000123";
	private HttpClient client;
	private FreeMobileActivationMessageSender sender;

	@BeforeEach
	void setUp() {
		client = mock(HttpClient.class);
		when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
		sender = new FreeMobileActivationMessageSender(client, ACCOUNT_ID, API_KEY, PHONE);
		clearInvocations(client);
	}

	@Test
	void sendsTheCodeOnlyInAJsonPostBodyToTheFixedHttpsEndpoint() throws Exception {
		HttpResponse<Void> response = response(200);
		sender.send(PHONE, CODE);

		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
		HttpRequest sent = request.getValue();
		assertEquals("https://smsapi.free-mobile.fr/sendmsg", sent.uri().toString());
		assertEquals("POST", sent.method());
		assertNull(sent.uri().getRawQuery());
		assertNull(sent.uri().getUserInfo());
		assertEquals("application/json; charset=UTF-8", sent.headers().firstValue("Content-Type").orElseThrow());
		assertEquals(Duration.ofSeconds(5), sent.timeout().orElseThrow());
		JsonNode body = JsonMapper.builder().build().readTree(body(sent));
		assertEquals(3, body.size());
		assertEquals(ACCOUNT_ID, body.path("user").asText());
		assertEquals(API_KEY, body.path("pass").asText());
		assertTrue(body.path("msg").asText().contains(CODE));
		assertTrue(body.path("msg").asText().contains("Gestion+"));
		assertTrue(body.path("msg").asText().length() <= 160);
		assertFalse(sent.toString().contains(API_KEY));
		assertFalse(sent.toString().contains(CODE));
		assertFalse(sent.toString().contains(PHONE));
		verify(response, never()).body();
	}

	@Test
	void escapesSpecialCharactersInTheApiKeyWithoutChangingTheirValue() throws Exception {
		String key = "fictitious-\"key\\&=+é";
		sender = new FreeMobileActivationMessageSender(client, ACCOUNT_ID, key, PHONE);
		response(200);
		sender.send(PHONE, CODE);
		ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client).send(request.capture(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
		JsonNode body = JsonMapper.builder().build().readTree(body(request.getValue()));
		assertEquals(key, body.path("pass").asText());
		assertEquals(3, body.size());
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "+12025550101", "12025550100", "+12025550100 ", " +12025550100" })
	void refusesEveryOtherRecipientBeforeCallingTheProvider(String phone) {
		ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
				() -> sender.send(phone, CODE));
		assertSafe(failure);
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@NullSource
	@ValueSource(strings = { "", "12345", "1234567", "123 56", "a23456", "123456\n", "１２３４５６", "١٢٣٤٥٦" })
	void rejectsMalformedCodesBeforeCallingTheProvider(String code) {
		assertThrows(IllegalArgumentException.class, () -> sender.send(PHONE, code));
		verifyNoInteractions(client);
	}

	@ParameterizedTest
	@ValueSource(ints = { 201, 202, 204, 301, 302, 307, 308, 400, 401, 402, 403, 429, 500, 503 })
	void reportsEveryNon200ResponseWithoutRetryingOrReadingTheResponseBody(int status) throws Exception {
		HttpResponse<Void> response = response(status);
		ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
				() -> sender.send(PHONE, CODE));
		assertSafe(failure);
		verify(client).send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
		verify(response, never()).body();
	}

	@Test
	void sanitizesNetworkFailuresWithoutRetrying() throws Exception {
		when(client.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
				.thenThrow(new IOException(API_KEY + " " + CODE + " " + PHONE));
		ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
				() -> sender.send(PHONE, CODE));
		assertSafe(failure);
		verify(client).send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
	}

	@Test
	void sanitizesTimeoutsWithoutRetrying() throws Exception {
		when(client.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
				.thenThrow(new HttpTimeoutException(API_KEY + " " + CODE));
		ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
				() -> sender.send(PHONE, CODE));
		assertSafe(failure);
		verify(client).send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any());
	}

	@Test
	void restoresTheInterruptFlagWithoutDisclosingTheCause() throws Exception {
		when(client.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
				.thenThrow(new InterruptedException(API_KEY + " " + CODE));

		try {
			ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
					() -> sender.send(PHONE, CODE));
			assertTrue(Thread.currentThread().isInterrupted());
			assertSafe(failure);
		} finally {
			Thread.interrupted();
		}

	}

	@ParameterizedTest
	@MethodSource("invalidConfigurations")
	void refusesInvalidConfigurationWithoutDisclosingItsValues(String accountId, String key, String recipient) {
		IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
				() -> new FreeMobileActivationMessageSender(client, accountId, key, recipient));
		assertFalse(failure.toString().contains(API_KEY));
		assertFalse(failure.toString().contains(PHONE));
		assertFalse(failure.toString().contains(ACCOUNT_ID));
		verifyNoInteractions(client);
	}

	@Test
	void refusesAnInjectedClientThatCouldFollowRedirects() {
		when(client.followRedirects()).thenReturn(HttpClient.Redirect.ALWAYS);
		assertThrows(IllegalArgumentException.class,
				() -> new FreeMobileActivationMessageSender(client, ACCOUNT_ID, API_KEY, PHONE));
	}

	@Test
	void logsOnlyTheStatusOrFailureTypeWithoutSecretsOrStackTraces() throws Exception {
		Logger logger = (Logger) org.slf4j.LoggerFactory.getLogger(FreeMobileActivationMessageSender.class);
		Level previous = logger.getLevel();
		ListAppender<ILoggingEvent> appender = new ListAppender<>();
		appender.start();
		logger.addAppender(appender);
		logger.setLevel(Level.WARN);

		try {
			response(403);
			assertThrows(ActivationMessageDeliveryException.class, () -> sender.send(PHONE, CODE));
			when(client.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any()))
					.thenThrow(new IOException(API_KEY + " " + CODE + " " + PHONE));
			assertThrows(ActivationMessageDeliveryException.class, () -> sender.send(PHONE, CODE));
		} finally {
			logger.detachAppender(appender);
			logger.setLevel(previous);
			appender.stop();
		}

		assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("HTTP 403")));
		assertTrue(appender.list.stream().anyMatch(event -> event.getFormattedMessage().contains("IOException")));

		for (ILoggingEvent event : appender.list) {
			assertNull(event.getThrowableProxy());
			assertFalse(event.getFormattedMessage().contains(API_KEY));
			assertFalse(event.getFormattedMessage().contains(CODE));
			assertFalse(event.getFormattedMessage().contains(PHONE));
			assertFalse(event.getFormattedMessage().contains(ACCOUNT_ID));
		}

	}

	@Test
	void releasesItsOwnHttpClient() {
		sender.close();
		verify(client).close();
	}

	private static Stream<Arguments> invalidConfigurations() {
		return Stream.of(Arguments.of(null, API_KEY, PHONE), Arguments.of("", API_KEY, PHONE),
				Arguments.of("1234567", API_KEY, PHONE), Arguments.of("123456789", API_KEY, PHONE),
				Arguments.of("abcdefgh", API_KEY, PHONE), Arguments.of(ACCOUNT_ID, null, PHONE),
				Arguments.of(ACCOUNT_ID, "", PHONE), Arguments.of(ACCOUNT_ID, " ", PHONE),
				Arguments.of(ACCOUNT_ID, "fictitious\nkey", PHONE), Arguments.of(ACCOUNT_ID, "fictitious key", PHONE),
				Arguments.of(ACCOUNT_ID, "x".repeat(257), PHONE), Arguments.of(ACCOUNT_ID, API_KEY, null),
				Arguments.of(ACCOUNT_ID, API_KEY, ""), Arguments.of(ACCOUNT_ID, API_KEY, "0612345678"),
				Arguments.of(ACCOUNT_ID, API_KEY, "+0123456789"),
				Arguments.of(ACCOUNT_ID, API_KEY, "+1234567890123456"));
	}

	private HttpResponse<Void> response(int status) throws Exception {
		HttpResponse<Void> response = mock();
		when(response.statusCode()).thenReturn(status);
		when(client.send(any(), ArgumentMatchers.<HttpResponse.BodyHandler<Void>>any())).thenReturn(response);
		return response;
	}

	private static void assertSafe(ActivationMessageDeliveryException failure) {
		assertNotNull(failure.getMessage());
		assertNull(failure.getCause());
		assertFalse(failure.toString().contains(API_KEY));
		assertFalse(failure.toString().contains(CODE));
		assertFalse(failure.toString().contains(PHONE));
		assertFalse(failure.toString().contains(ACCOUNT_ID));
	}

	private static byte[] body(HttpRequest request) throws Exception {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		CompletableFuture<byte[]> completed = new CompletableFuture<>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ByteBuffer item) {
				byte[] chunk = new byte[item.remaining()];
				item.get(chunk);
				bytes.writeBytes(chunk);
			}

			@Override
			public void onError(Throwable failure) {
				completed.completeExceptionally(failure);
			}

			@Override
			public void onComplete() {
				completed.complete(bytes.toByteArray());
			}
		});
		return completed.get(5, TimeUnit.SECONDS);
	}
}
