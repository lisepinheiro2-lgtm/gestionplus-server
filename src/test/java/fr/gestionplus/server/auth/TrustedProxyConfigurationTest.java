package fr.gestionplus.server.auth;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicReference;

import org.apache.catalina.connector.Connector;
import org.apache.catalina.connector.Request;
import org.apache.catalina.connector.Response;
import org.apache.catalina.valves.RemoteIpValve;
import org.apache.catalina.valves.ValveBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class TrustedProxyConfigurationTest {

	@ParameterizedTest
	@ValueSource(strings = { "127.0.0.1", "::1", "192.0.2.10,2001:db8::10", "192.0.2.0/24", "2001:db8::/64" })
	void acceptsExplicitAddressesAndNetworks(String proxies) {
		assertDoesNotThrow(() -> new TrustedProxyConfiguration(proxies));
	}

	@ParameterizedTest
	@NullAndEmptySource
	@ValueSource(strings = { " ", ".*", "localhost", "0.0.0.0/0", "::/0", "0.0.0.0", "::", "192.0.2.1,",
			"192.0.2.1/33", "2001:db8::/129", "127.0.0.1:8080", "fe80::1%1", "192.0.2.1/-1" })
	void refusesMissingOrUnrestrictedTrust(String proxies) {
		assertThrows(IllegalArgumentException.class, () -> new TrustedProxyConfiguration(proxies));
	}

	@Test
	void ignoresForwardedAddressesFromAnUntrustedPeer() throws Exception {
		assertEquals("198.51.100.10", observedAddress("192.0.2.10/32", "198.51.100.10", "203.0.113.20"));
	}

	@Test
	void acceptsTheForwardedAddressOnlyFromTheConfiguredProxy() throws Exception {
		assertEquals("203.0.113.20", observedAddress("192.0.2.10/32", "192.0.2.10", "203.0.113.20"));
	}

	@Test
	void supportsExplicitTrustedProxyNetworks() throws Exception {
		assertEquals("203.0.113.20", observedAddress("192.0.2.0/24", "192.0.2.10", "203.0.113.20"));
	}

	private String observedAddress(String trust, String peer, String forwarded) throws Exception {
		var coyote = new org.apache.coyote.Request();
		coyote.scheme().setString("http");
		coyote.serverName().setString("localhost");
		coyote.setServerPort(8080);
		coyote.getMimeHeaders().addValue("x-forwarded-for").setString(forwarded);
		Request request = new Request(new Connector(), coyote);
		request.setRemoteAddr(peer);
		request.setRemoteHost(peer);
		RemoteIpValve valve = new RemoteIpValve();
		valve.setInternalProxies(trust);
		AtomicReference<String> observed = new AtomicReference<>();
		valve.setNext(new ValveBase() {
			@Override
			public void invoke(Request current, Response response) {
				observed.set(current.getRemoteAddr());
			}
		});
		valve.invoke(request, new Response(new org.apache.coyote.Response()));
		return observed.get();
	}
}
