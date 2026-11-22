package fr.gestionplus.server.auth;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextImpl;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.DefaultCsrfToken;

import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

public final class SessionValueSerializer implements RedisSerializer<Object> {

	private static final int MAX_BYTES = 65536;
	private static final JsonMapper JSON = JsonMapper.builder()
			.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

	@Override
	public byte[] serialize(Object value) throws SerializationException {

		if (value == null) {
			return new byte[0];
		}

		try {
			byte[] bytes = JSON.writeValueAsBytes(encode(value, 0));

			if (bytes.length > MAX_BYTES) {
				throw invalid();
			}

			return bytes;
		} catch (RuntimeException failure) {
			throw invalid();
		}

	}

	@Override
	public Object deserialize(byte[] bytes) throws SerializationException {

		if (bytes == null || bytes.length == 0) {
			return null;
		}

		if (bytes.length > MAX_BYTES) {
			throw invalid();
		}

		try {
			return decode(JSON.readTree(bytes), 0);
		} catch (RuntimeException failure) {
			throw invalid();
		}

	}

	private ObjectNode encode(Object value, int depth) {

		if (depth > 8) {
			throw invalid();
		}

		ObjectNode node = JSON.createObjectNode();

		if (value instanceof String text) {
			node.put("type", "string").put("value", text);
		} else if (value instanceof Long number) {
			node.put("type", "long").put("value", number);
		} else if (value instanceof Integer number) {
			node.put("type", "integer").put("value", number);
		} else if (value instanceof Boolean flag) {
			node.put("type", "boolean").put("value", flag);
		} else if (value instanceof CsrfToken token) {
			node.put("type", "csrf").put("header", token.getHeaderName())
					.put("parameter", token.getParameterName()).put("value", token.getToken());
		} else if (value instanceof SecurityContext context) {
			encodeContext(node, context);
		} else if (value instanceof Map<?, ?> map && map.size() <= 64) {
			node.put("type", "map");
			ObjectNode values = node.putObject("value");
			map.forEach((key, item) -> {

				if (!(key instanceof String name) || item == null) {
					throw invalid();
				}

				values.set(name, encode(item, depth + 1));
			});
		} else {
			throw invalid();
		}

		return node;
	}

	private Object decode(JsonNode node, int depth) {

		if (depth > 8 || node == null || !node.isObject()) {
			throw invalid();
		}

		return switch (text(node, "type")) {
		case "string" -> text(node, "value");
		case "long" -> number(node, "value");
		case "integer" -> Math.toIntExact(number(node, "value"));
		case "boolean" -> flag(node, "value");
		case "csrf" -> new DefaultCsrfToken(text(node, "header"), text(node, "parameter"), text(node, "value"));
		case "context" -> decodeContext(node);
		case "enrollment-context" -> decodeEnrollmentContext(node);
		case "map" -> {
			JsonNode values = node.get("value");

			if (values == null || !values.isObject() || values.size() > 64) {
				throw invalid();
			}

			Map<String, Object> map = new LinkedHashMap<>();
			values.properties().forEach(entry -> map.put(entry.getKey(), decode(entry.getValue(), depth + 1)));
			yield map;
		}
		default -> throw invalid();
		};
	}

	private void encodeContext(ObjectNode node, SecurityContext context) {
		var authentication = context.getAuthentication();

		if (!(authentication instanceof UsernamePasswordAuthenticationToken) || !authentication.isAuthenticated()
				|| authentication.getCredentials() != null) {
			throw invalid();
		}
		if (authentication.getPrincipal() instanceof EnrollmentPrincipal principal) {
			if (authentication.getDetails() != null || authentication.getAuthorities().size() != 1
					|| !EnrollmentPrincipal.AUTHORITY.equals(authentication.getAuthorities().iterator().next().getAuthority())) {
				throw invalid();
			}
			node.put("type", "enrollment-context").put("userID", principal.userID().toString())
					.put("version", principal.sessionVersion()).put("expiresAt", principal.expiresAt().toString());
			return;
		}
		if (!(authentication.getPrincipal() instanceof UserAccount account)
				|| authentication.getAuthorities().stream().anyMatch(value -> EnrollmentPrincipal.AUTHORITY.equals(value.getAuthority()))) {
			throw invalid();
		}

		node.put("type", "context").put("userID", account.getUserID().toString())
				.put("userName", account.getUserName()).put("status", account.getStatu().name())
				.put("version", account.getSessionVersion()).put("lastName", account.getLastName())
				.put("firstName", account.getFirstName()).put("email", account.getEmail())
				.put("phone", account.getPhoneNumber());

		if (account.getEmployeeID() != null) {
			node.put("employeeID", account.getEmployeeID().toString());
		}

		var roles = node.putArray("roles");
		account.getRoles().stream().map(Enum::name).sorted().forEach(roles::add);
		var authorities = node.putArray("authorities");
		authentication.getAuthorities().forEach(authority -> authorities.add(authority.getAuthority()));

		if (authentication.getDetails() instanceof GestionAuthenticationProvider.LoginAssurance assurance) {
			node.put("mfaVerified", assurance.mfaVerified());
		} else if (authentication.getDetails() != null) {
			throw invalid();
		}

	}

	private SecurityContext decodeContext(JsonNode node) {
		Set<UserRole> roles = strings(node, "roles").stream().map(UserRole::valueOf).collect(Collectors.toSet());
		UserAccount account = new UserAccount(UUID.fromString(text(node, "userID")), text(node, "userName"),
				UserAccountStatus.valueOf(text(node, "status")), roles,
				node.has("employeeID") ? UUID.fromString(text(node, "employeeID")) : null,
				text(node, "lastName"), text(node, "firstName"), text(node, "email"), text(node, "phone"),
				number(node, "version"));
		var authorities = strings(node, "authorities").stream().map(SimpleGrantedAuthority::new).toList();
		if (authorities.stream().anyMatch(value -> EnrollmentPrincipal.AUTHORITY.equals(value.getAuthority()))) {
			throw invalid();
		}
		var authentication = UsernamePasswordAuthenticationToken.authenticated(account, null, authorities);

		if (node.has("mfaVerified")) {
			authentication.setDetails(new GestionAuthenticationProvider.LoginAssurance(flag(node, "mfaVerified")));
		}

		return new SecurityContextImpl(authentication);
	}

	private SecurityContext decodeEnrollmentContext(JsonNode node) {
		if (node.size() != 4) {
			throw invalid();
		}
		EnrollmentPrincipal principal = new EnrollmentPrincipal(UUID.fromString(text(node, "userID")),
				number(node, "version"), Instant.parse(text(node, "expiresAt")));
		return new SecurityContextImpl(UsernamePasswordAuthenticationToken.authenticated(principal, null,
				java.util.List.of(new SimpleGrantedAuthority(EnrollmentPrincipal.AUTHORITY))));
	}

	private java.util.List<String> strings(JsonNode node, String field) {
		JsonNode values = node.get(field);

		if (values == null || !values.isArray() || values.size() > 64) {
			throw invalid();
		}

		return StreamSupport.stream(values.spliterator(), false).map(value -> {

			if (!value.isTextual()) {
				throw invalid();
			}

			return value.asString();
		}).collect(Collectors.toCollection(ArrayList::new));
	}

	private String text(JsonNode node, String field) {
		JsonNode value = node.get(field);

		if (value == null || !value.isTextual()) {
			throw invalid();
		}

		return value.asString();
	}

	private long number(JsonNode node, String field) {
		JsonNode value = node.get(field);

		if (value == null || !value.isIntegralNumber() || !value.canConvertToLong()) {
			throw invalid();
		}

		return value.longValue();
	}

	private boolean flag(JsonNode node, String field) {
		JsonNode value = node.get(field);

		if (value == null || !value.isBoolean()) {
			throw invalid();
		}

		return value.booleanValue();
	}

	private SerializationException invalid() {
		return new SerializationException("Les données de session ne peuvent pas être traitées.");
	}
}
