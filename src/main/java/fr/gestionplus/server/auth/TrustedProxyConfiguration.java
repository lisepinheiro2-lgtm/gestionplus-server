package fr.gestionplus.server.auth;

import java.net.InetAddress;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("reverse-proxy")
public class TrustedProxyConfiguration {

	public TrustedProxyConfiguration(@Value("${GESTIONPLUS_TRUSTED_PROXIES}") String proxies) {

		if (proxies == null || proxies.isBlank()) {
			throw invalid();
		}

		for (String configured : proxies.split(",", -1)) {
			String[] parts = configured.strip().split("/", -1);

			try {

				if (parts.length > 2 || parts[0].contains("%")) {
					throw invalid();
				}

				InetAddress address = InetAddress.ofLiteral(parts[0]);

				if (address.isAnyLocalAddress() || address.isMulticastAddress()) {
					throw invalid();
				}

				if (parts.length == 2) {
					int prefix = Integer.parseInt(parts[1]);

					if (prefix < 1 || prefix > address.getAddress().length * 8) {
						throw invalid();
					}

				}

			} catch (IllegalArgumentException failure) {
				throw invalid();
			}

		}

	}

	private IllegalArgumentException invalid() {
		return new IllegalArgumentException(
				"Les proxies de confiance doivent être des adresses IP ou des réseaux CIDR explicites.");
	}
}
