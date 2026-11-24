package fr.gestionplus.server.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import fr.gestionplus.server.account.ActivationMessageDeliveryException;
import tools.jackson.databind.json.JsonMapper;

class OvhSmsActivationMessageSenderTest {

	private static final String PHONE = "+12025550100";
	private static final String REPORT = "{\"validReceivers\":[\"" + PHONE
			+ "\"],\"invalidReceivers\":[],\"ids\":[123]}";
	private final HttpClient client = mock(HttpClient.class);
	private OvhSmsActivationMessageSender sender;

	@BeforeEach
	void setUp() {
		when(client.followRedirects()).thenReturn(HttpClient.Redirect.NEVER);
		sender = new OvhSmsActivationMessageSender(client, "a".repeat(32), "b".repeat(32), "c".repeat(32),
				"sms-fictitious-1", "GestionPlus");
	}

	@Test
	void signsASingleBoundedPostForExactlyOneRecipient() throws Exception {
		responses(200, REPORT);
		sender.send(PHONE, "000042");
		ArgumentCaptor<HttpRequest> requests = ArgumentCaptor.forClass(HttpRequest.class);
		verify(client, times(2)).sendAsync(requests.capture(),
				ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
		assertEquals("https://api.eu.ovhcloud.com/1.0/auth/time", requests.getAllValues().getFirst().uri().toString());
		HttpRequest post = requests.getAllValues().getLast();
		assertEquals("https://api.eu.ovhcloud.com/1.0/sms/sms-fictitious-1/jobs", post.uri().toString());
		assertEquals("POST", post.method());
		String body = body(post);
		var json = JsonMapper.builder().build().readTree(body);
		assertEquals(PHONE, json.get("receivers").get(0).asString());
		assertEquals(1, json.get("receivers").size());
		assertTrue(json.get("message").asString().contains("000042"));
		assertTrue(json.get("noStopClause").booleanValue());
		assertEquals(5, json.get("validityPeriod").intValue());
		String material = "b".repeat(32) + "+" + "c".repeat(32) + "+POST+" + post.uri() + "+" + body
				+ "+1795262400";
		String expected = "$1$" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1")
				.digest(material.getBytes(StandardCharsets.UTF_8)));
		assertEquals(expected, post.headers().firstValue("X-Ovh-Signature").orElseThrow());
		assertFalse(post.toString().contains(PHONE));
		assertFalse(post.toString().contains("000042"));
	}

	@ParameterizedTest
	@ValueSource(ints = { 201, 202, 301, 400, 401, 403, 429, 500, 503 })
	void refusesProviderFailuresWithoutRetrying(int status) {
		responses(status, REPORT);
		ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
				() -> sender.send(PHONE, "000042"));
		assertNull(failure.getCause());
		verify(client, times(2)).sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any());
	}

	@ParameterizedTest
	@ValueSource(strings = { "null", "{}", "not-json", "{\"validReceivers\":[],\"invalidReceivers\":[],\"ids\":[1]}",
			"{\"validReceivers\":[\"+12025550199\"],\"invalidReceivers\":[],\"ids\":[1]}",
			"{\"validReceivers\":[\"+12025550100\"],\"invalidReceivers\":[\"+12025550100\"],\"ids\":[1]}",
			"{\"validReceivers\":[\"+12025550100\"],\"invalidReceivers\":[],\"ids\":[]}" })
	void requiresAnExplicitAcceptanceReportForTheRequestedRecipient(String report) {
		responses(200, report);
		assertThrows(ActivationMessageDeliveryException.class, () -> sender.send(PHONE, "000042"));
	}

	@Test
	void cancelsAnInterruptedRequestAndKeepsSecretsOutOfTheException() {
		var pending = new CompletableFuture<HttpResponse<String>>();
		when(client.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
		Thread.currentThread().interrupt();

		try {
			ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
					() -> sender.send(PHONE, "000042"));
			assertTrue(Thread.currentThread().isInterrupted());
			assertTrue(pending.isCancelled());
			assertNull(failure.getCause());
		} finally {
			Thread.interrupted();
		}

	}

	@Test
	void cannotEnableOvhTogetherWithTheFakeSender() {
		new ApplicationContextRunner().withUserConfiguration(OvhSmsConfiguration.class)
				.withPropertyValues("gestionplus.sms.provider=ovh", "gestionplus.sms.fictif=true")
				.run(context -> assertTrue(context.getStartupFailure() != null));
	}

	@Test
	void staysDisabledUntilTheProviderIsExplicitlySelected() {
		new ApplicationContextRunner().withUserConfiguration(OvhSmsConfiguration.class)
				.run(context -> assertTrue(context.getBeansOfType(OvhSmsActivationMessageSender.class).isEmpty()));
	}

	@Test
	void cancelsTheRequestWhenItsOverallDeadlineIsReached() throws Exception {
		CompletableFuture<HttpResponse<String>> pending = mock();
		when(pending.get(5, TimeUnit.SECONDS))
				.thenThrow(new java.util.concurrent.TimeoutException("private-test-value"));
		when(client.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any())).thenReturn(pending);
		ActivationMessageDeliveryException failure = assertThrows(ActivationMessageDeliveryException.class,
				() -> sender.send(PHONE, "000042"));
		assertNull(failure.getCause());
		assertFalse(failure.toString().contains("private-test-value"));
		verify(pending).cancel(true);
	}

	@Test
	void permitsProductionConfigurationWithOnlyExternalFictitiousCredentialsAndWithoutSending() {
		new ApplicationContextRunner().withUserConfiguration(OvhSmsConfiguration.class)
				.withPropertyValues("gestionplus.sms.provider=ovh", "spring.profiles.active=prod",
						"gestionplus.sms.ovh.application-key=" + "a".repeat(32),
						"gestionplus.sms.ovh.application-secret=" + "b".repeat(32),
						"gestionplus.sms.ovh.consumer-key=" + "c".repeat(32),
						"gestionplus.sms.ovh.service-name=sms-fictitious-1", "gestionplus.sms.ovh.sender=GestionPlus")
				.run(context -> {
					assertNull(context.getStartupFailure());
					assertEquals(1, context.getBeansOfType(OvhSmsActivationMessageSender.class).size());
				});
	}

	private void responses(int status, String report) {
		HttpResponse<String> time = mock();
		when(time.statusCode()).thenReturn(200);
		when(time.body()).thenReturn("1795262400");
		HttpResponse<String> result = mock();
		when(result.statusCode()).thenReturn(status);
		when(result.body()).thenReturn(report);
		when(client.sendAsync(any(), ArgumentMatchers.<HttpResponse.BodyHandler<String>>any()))
				.thenReturn(CompletableFuture.completedFuture(time), CompletableFuture.completedFuture(result));
	}

	private static String body(HttpRequest request) throws Exception {
		var bytes = new java.io.ByteArrayOutputStream();
		var done = new CompletableFuture<String>();
		request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
			@Override
			public void onSubscribe(Flow.Subscription subscription) {
				subscription.request(Long.MAX_VALUE);
			}

			@Override
			public void onNext(ByteBuffer chunk) {
				byte[] value = new byte[chunk.remaining()];
				chunk.get(value);
				bytes.writeBytes(value);
			}

			@Override
			public void onError(Throwable failure) {
				done.completeExceptionally(failure);
			}

			@Override
			public void onComplete() {
				done.complete(bytes.toString(StandardCharsets.UTF_8));
			}
		});
		return done.get(5, TimeUnit.SECONDS);
	}
}
