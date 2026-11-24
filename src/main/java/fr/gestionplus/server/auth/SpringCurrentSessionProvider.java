package fr.gestionplus.server.auth;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import fr.gestionplus.server.account.CurrentSessionProvider;
import jakarta.servlet.http.HttpSession;

@Component
public class SpringCurrentSessionProvider implements CurrentSessionProvider{

	@Override
	public String getCurrentSessionIDHash() {

		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();

		if (!(attributes instanceof ServletRequestAttributes)) {
			return null;
		}

		ServletRequestAttributes requestAttributes = (ServletRequestAttributes) attributes;
		HttpSession session = requestAttributes.getRequest().getSession(false);

		if (session == null) {
			return null;
		}

		String sessionID = session.getId();

		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
					.digest(sessionID.getBytes(StandardCharsets.UTF_8)));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("Impossible de générer l'empreinte de session.", e);
		}

	}

}
