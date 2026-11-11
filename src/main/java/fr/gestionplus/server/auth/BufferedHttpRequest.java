package fr.gestionplus.server.auth;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URLDecoder;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.springframework.http.MediaType;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;

final class BufferedHttpRequest extends HttpServletRequestWrapper {

	private final Map<String, String[]> parameters = new LinkedHashMap<>();
	private final ByteArrayInputStream body;
	private final boolean hasBody;
	private final ServletInputStream input;
	private boolean inputRequested;
	private BufferedReader reader;
	private boolean listenerRegistered;

	BufferedHttpRequest(HttpServletRequest request, int maxBytes, int maxParameters) throws IOException {
		super(request);

		byte[] bytes = request.getInputStream().readNBytes(maxBytes + 1);

		if (bytes.length > maxBytes) {
			throw new InvalidParameterException("Corps HTTP trop volumineux.", 413);
		}

		request.getParameterMap().forEach((name, values) -> parameters.put(name, values.clone()));
		int count = parameters.values().stream().mapToInt(values -> values.length).sum();

		if (count > maxParameters) {
			throw new InvalidParameterException("Trop de paramètres HTTP.");
		}

		String type = request.getContentType();

		if (type != null && Set.of("POST", "PUT", "PATCH", "DELETE").contains(request.getMethod())) {
			MediaType mediaType = MediaType.parseMediaType(type);

			if ("application".equals(mediaType.getType())
					&& "x-www-form-urlencoded".equals(mediaType.getSubtype())) {
				Charset charset = request.getCharacterEncoding() == null ? StandardCharsets.ISO_8859_1
						: Charset.forName(request.getCharacterEncoding());

				for (String field : new String(bytes, charset).split("&")) {

					if (field.isEmpty()) {
						continue;
					}

					if (++count > maxParameters) {
						throw new InvalidParameterException("Trop de paramètres HTTP.");
					}

					if (!"POST".equals(request.getMethod())) {
						continue;
					}

					int separator = field.indexOf('=');
					String name = URLDecoder.decode(separator < 0 ? field : field.substring(0, separator), charset);
					String value = URLDecoder.decode(separator < 0 ? "" : field.substring(separator + 1), charset);
					String[] previous = parameters.get(name);

					if (previous == null) {
						parameters.put(name, new String[] { value });
					} else {
						String[] values = Arrays.copyOf(previous, previous.length + 1);
						values[previous.length] = value;
						parameters.put(name, values);
					}

				}

			}

		}

		hasBody = bytes.length != 0;
		body = new ByteArrayInputStream(bytes);
		input = new ServletInputStream() {
			@Override
			public int read() {
				return body.read();
			}

			@Override
			public int read(byte[] buffer, int offset, int length) {
				return body.read(buffer, offset, length);
			}

			@Override
			public boolean isFinished() {
				return body.available() == 0;
			}

			@Override
			public boolean isReady() {
				return true;
			}

			@Override
			public void setReadListener(ReadListener listener) {

				if (listener == null || listenerRegistered || !isAsyncStarted()) {
					throw new IllegalStateException("Lecture asynchrone indisponible.");
				}

				listenerRegistered = true;
				getAsyncContext().start(() -> {

					try {

						if (!isFinished()) {
							listener.onDataAvailable();
						}

						if (isFinished()) {
							listener.onAllDataRead();
						}

					} catch (IOException failure) {
						listener.onError(failure);
					}

				});
			}
		};
	}

	boolean hasBody() {
		return hasBody;
	}

	@Override
	public ServletInputStream getInputStream() {

		if (reader != null) {
			throw new IllegalStateException("Le lecteur de caractères est déjà utilisé.");
		}

		inputRequested = true;
		return input;
	}

	@Override
	public BufferedReader getReader() {

		if (inputRequested) {
			throw new IllegalStateException("Le flux binaire est déjà utilisé.");
		}

		if (reader == null) {
			Charset charset = getCharacterEncoding() == null ? StandardCharsets.ISO_8859_1
					: Charset.forName(getCharacterEncoding());
			reader = new BufferedReader(new InputStreamReader(input, charset));
		}

		return reader;
	}

	@Override
	public String getParameter(String name) {
		String[] values = parameters.get(name);
		return values == null || values.length == 0 ? null : values[0];
	}

	@Override
	public String[] getParameterValues(String name) {
		String[] values = parameters.get(name);
		return values == null ? null : values.clone();
	}

	@Override
	public Enumeration<String> getParameterNames() {
		return Collections.enumeration(parameters.keySet());
	}

	@Override
	public Map<String, String[]> getParameterMap() {
		Map<String, String[]> result = new LinkedHashMap<>();
		parameters.forEach((name, values) -> result.put(name, values.clone()));
		return Collections.unmodifiableMap(result);
	}
}
