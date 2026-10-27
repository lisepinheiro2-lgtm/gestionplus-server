package fr.gestionplus.server.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class PwnedPasswordsCheckerTest {

	private HttpClient client;
	private PwnedPasswordsChecker checker;

	@BeforeEach
	void setUp() {
		client = Mockito.mock(HttpClient.class);
		checker = new PwnedPasswordsChecker(client);
	}

	@SuppressWarnings("unchecked")
	private void mockHttpResponse(int statusCode, String responseText) throws IOException, InterruptedException {

		HttpResponse<String> response = Mockito.mock(HttpResponse.class);

		Mockito.when(response.statusCode()).thenReturn(statusCode);
		Mockito.when(response.body()).thenReturn(responseText);

		Mockito.when(client.send(Mockito.any(), Mockito.<HttpResponse.BodyHandler<String>>any())).thenReturn(response);
	}

	@Test
	void shouldThrowWhenHttpStatusIs429() throws IOException, InterruptedException {

		mockHttpResponse(429, null);

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> checker.isCompromised("test"));

		assertEquals("Échec de la vérification du mot de passe. Code HTTP : 429", exception.getMessage());
	}

	@Test
	void compromisedPasswordWhenHttpStatusIs200() throws IOException, InterruptedException, NoSuchAlgorithmException {

		String password = "test";
		byte[] bytes = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
		String responseText = HexFormat.of().formatHex(bytes).toUpperCase().substring(5) + ":30";

		mockHttpResponse(200, responseText);

		assertTrue(checker.isCompromised(password));
	}

	@Test
	void notCompromisedPasswordWhenHttpStatusIs200()
			throws IOException, InterruptedException, NoSuchAlgorithmException {

		String password = "test";
		byte[] bytes = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
		String suffix = HexFormat.of().formatHex(bytes).toUpperCase().substring(5);
		String responseText = "0" + suffix.substring(1) + ":30";

		mockHttpResponse(200, responseText);

		assertFalse(checker.isCompromised(password));
	}

	@Test
	void notCompromisedWhenHttpStatusIs200AndCountIs0()
			throws IOException, InterruptedException, NoSuchAlgorithmException {

		String password = "test";
		byte[] bytes = MessageDigest.getInstance("SHA-1").digest(password.getBytes(StandardCharsets.UTF_8));
		String responseText = HexFormat.of().formatHex(bytes).toUpperCase().substring(5) + ":00";

		mockHttpResponse(200, responseText);

		assertFalse(checker.isCompromised(password));
	}

	@Test
	void shouldThrowWhenHttpStatusIs200AndResponseBodyIsBlank() throws IOException, InterruptedException {

		mockHttpResponse(200, "    ");

		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> checker.isCompromised("test"));

		assertEquals("Réponse invalide du service de vérification des mots de passe.", exception.getMessage());
	}

	@Test
	void shouldThrowWhenHttpStatusIs200AndResponseIsNull() throws IOException, InterruptedException {

		mockHttpResponse(200, null);

		assertThrows(IllegalStateException.class, () -> checker.isCompromised("test"));
	}

	@Test
	void shouldThrowWhenNetworkFails() throws IOException, InterruptedException {

		IOException networkFailure = new IOException("Panne réseau");
		Mockito.when(client.send(Mockito.any(), Mockito.<HttpResponse.BodyHandler<String>>any()))
				.thenThrow(networkFailure);
		IllegalStateException exception = assertThrows(IllegalStateException.class,
				() -> checker.isCompromised("test"));

		assertSame(networkFailure, exception.getCause());
	}
}