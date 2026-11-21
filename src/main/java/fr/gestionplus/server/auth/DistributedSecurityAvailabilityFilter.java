package fr.gestionplus.server.auth;

import java.io.IOException;

import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.Ordered;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.serializer.SerializationException;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import fr.gestionplus.server.api.HttpApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

@Component
public final class DistributedSecurityAvailabilityFilter extends FilterRegistrationBean<OncePerRequestFilter> {

	public DistributedSecurityAvailabilityFilter() {
		super(new OncePerRequestFilter() {
			@Override
			protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
					throws ServletException, IOException {

				try {
					chain.doFilter(request, response);
				} catch (DistributedSecurityUnavailableException | DataAccessException
						| SerializationException failure) {

					if (response.isCommitted()) {
						throw failure;
					}

					response.resetBuffer();
					HttpApiError.SERVICE_UNAVAILABLE.write(response);
				}

			}
		});
		setOrder(Ordered.HIGHEST_PRECEDENCE);
	}
}
