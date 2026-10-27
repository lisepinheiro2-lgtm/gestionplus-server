package fr.gestionplus.server.audit;

import java.util.UUID;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import fr.gestionplus.server.auth.UserAccount;

@Component
public class SpringAuditContextProvider implements AuditContextProvider {

	@Override
	public UUID getActorUserId() {

		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

		if (authentication == null || !authentication.isAuthenticated())
			return null;

		Object principal = authentication.getPrincipal();

		if (!(principal instanceof UserAccount))
			return null;

		UserAccount account = (UserAccount) principal;

		return account.getUserID();
	}

	@Override
	public String getIpAddress() {

		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();

		if (!(attributes instanceof ServletRequestAttributes))
			return null;

		ServletRequestAttributes requestAttributes = (ServletRequestAttributes) attributes;

		return requestAttributes.getRequest().getRemoteAddr();
	}

}
