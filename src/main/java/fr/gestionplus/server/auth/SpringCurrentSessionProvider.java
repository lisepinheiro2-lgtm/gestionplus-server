package fr.gestionplus.server.auth;

import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import fr.gestionplus.server.account.CurrentSessionProvider;
import jakarta.servlet.http.HttpSession;

@Component
public class SpringCurrentSessionProvider implements CurrentSessionProvider {

	@Override
	public String getCurrentSessionIDHash() {

		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();

		if (!(attributes instanceof ServletRequestAttributes requestAttributes)) {
			return null;
		}

		HttpSession session = requestAttributes.getRequest().getSession(false);

		if (session == null) {
			return null;
		}

		return SessionIDHasher.hash(session.getId());

	}

}
