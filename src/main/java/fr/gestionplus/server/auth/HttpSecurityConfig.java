package fr.gestionplus.server.auth;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.access.hierarchicalroles.RoleHierarchy;
import org.springframework.security.access.hierarchicalroles.RoleHierarchyImpl;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.logout.SecurityContextLogoutHandler;
import org.springframework.security.web.context.DelegatingSecurityContextRepository;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.RequestAttributeSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.security.web.csrf.CsrfTokenRepository;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.web.filter.FormContentFilter;

import fr.gestionplus.server.account.AccountStore;
import fr.gestionplus.server.api.AccountActivationRoutes;
import fr.gestionplus.server.api.HttpApiError;
import fr.gestionplus.server.api.MfaEnrollmentRoutes;
import fr.gestionplus.server.api.OrganizationalStructureRoutes;
import fr.gestionplus.server.audit.AuditReason;
import fr.gestionplus.server.audit.AuditService;
import fr.gestionplus.server.audit.AuditedAccessDeniedException;
import jakarta.servlet.DispatcherType;

@Configuration
public class HttpSecurityConfig {

	@Bean
	public RoleHierarchy roleHierarchy() {
		return RoleHierarchyImpl.fromHierarchy("""
				ROLE_SUPER_ADMIN > ROLE_ADMIN
				ROLE_ADMIN > ROLE_R_H
				ROLE_ADMIN > ROLE_ACCOUNTANT
				ROLE_R_H > ROLE_MANAGER
				ROLE_R_H > ROLE_SECRETARY
				ROLE_MANAGER > ROLE_TEAM_LEAD
				ROLE_SECRETARY > ROLE_TEAM_LEAD
				ROLE_TEAM_LEAD > ROLE_EMPLOYEE
				""");
	}

	@Bean
	public SecurityFilterChain securityFilterChain(HttpSecurity http, SessionRegistry registry,
			AccountStore accountStore, AuditService auditService, SessionLifetime lifetime, AccountMfaService mfa,
			HttpRequestPolicyFilter requestPolicy, ObjectProvider<FormContentFilter> formContentFilters,
			RequestRateLimiter rateLimiter, SecurityContextRepository securityContextRepository,
			CsrfTokenRepository csrfTokenRepository) throws Exception {

		HttpSecurityAudit audit = new HttpSecurityAudit(auditService);
		SecurityContextLogoutHandler logoutHandler = new SecurityContextLogoutHandler();

		http.securityContext(config -> config.securityContextRepository(securityContextRepository));
		http.csrf(config -> config.csrfTokenRepository(csrfTokenRepository));

		http.formLogin(form -> form.permitAll()
				.authenticationDetailsSource(request -> new GestionAuthenticationProvider.LoginDetails(
						request.getRemoteAddr(), request.getParameter("totp")))
				.successHandler((request, response, authentication) -> {

					try {
						audit.loginSucceeded(request, authentication);
						lifetime.start(request.getSession(true),
								authentication
										.getDetails() instanceof GestionAuthenticationProvider.LoginAssurance assurance
										&& assurance.mfaVerified());
						response.setStatus(200);
					} catch (RuntimeException failure) {
						logoutHandler.logout(request, response, authentication);
						HttpApiError.SERVICE_UNAVAILABLE.write(response);
					}

				}).failureHandler((request, response, exception) -> {
					audit.loginFailed(request, exception);

					if (exception instanceof DistributedSecurityUnavailableException) {
						HttpApiError.SERVICE_UNAVAILABLE.write(response);
					} else {
						HttpApiError.AUTHENTICATION_REQUIRED.write(response);
					}

				}));

		http.exceptionHandling(errors -> errors.authenticationEntryPoint((request, response, exception) -> {

			if (exception instanceof DistributedSecurityUnavailableException) {
				HttpApiError.SERVICE_UNAVAILABLE.write(response);
				return;
			}

			audit.refused(request, currentPrincipal(), AuditReason.AUTHENTICATION_REQUIRED);
			HttpApiError.AUTHENTICATION_REQUIRED.write(response);
		}).accessDeniedHandler((request, response, exception) -> {
			AuditReason reason = AuditReason.ACCESS_NOT_AUTHORIZED;

			if (exception instanceof CsrfException) {
				reason = AuditReason.CSRF_REJECTED;
			} else if (exception instanceof AuditedAccessDeniedException denied) {
				reason = denied.getReason();
			}

			audit.refused(request, currentPrincipal(), reason);
			HttpApiError.fromAccessDenied(exception).write(response);
		}));

		http.logout(logout -> logout
				.addLogoutHandler((request, response, authentication) -> lifetime.end(request.getSession(false)))
				.logoutSuccessHandler((request, response, authentication) -> {
					audit.logout(request, authentication);
					response.setStatus(204);
				}));

		http.requestCache(cache -> cache.disable());
		http.authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/csrf").permitAll()
				.requestMatchers(HttpMethod.GET, "/api/auth/me", SessionLifetime.STATUS_PATH).hasAuthority("ROLE_USER")
				.requestMatchers(HttpMethod.GET, OrganizationalStructureRoutes.STRUCTURES_PATH,
						OrganizationalStructureRoutes.STRUCTURE_PATH)
				.hasAuthority("ROLE_USER")
				.requestMatchers(HttpMethod.POST, AccountActivationRoutes.SMS_CHALLENGE_PATH).permitAll()
				.requestMatchers(HttpMethod.POST, AccountActivationRoutes.ACTIVATION_PATH).permitAll()
				.requestMatchers(HttpMethod.POST, MfaEnrollmentRoutes.VOLUNTARY_REQUEST_PATH,
						MfaEnrollmentRoutes.VOLUNTARY_BEGIN_PATH, MfaEnrollmentRoutes.VOLUNTARY_COMPLETE_PATH)
				.hasAuthority("ROLE_USER")
				.requestMatchers(HttpMethod.POST, MfaEnrollmentRoutes.ENROLLMENT_PATH)
				.hasAuthority("ROLE_MFA_ENROLLMENT")
				.requestMatchers(HttpMethod.POST, MfaEnrollmentRoutes.ENROLLMENT_COMPLETE_PATH)
				.hasAuthority("ROLE_MFA_ENROLLMENT")
				.requestMatchers(HttpMethod.POST, MfaEnrollmentRoutes.ENROLLMENT_SESSION_PATH).permitAll()
				.anyRequest().denyAll());

		http.sessionManagement(sessions -> sessions.maximumSessions(-1).sessionRegistry(registry)
				.expiredSessionStrategy(event -> {
					audit.refused(event.getRequest(), event.getSessionInformation().getPrincipal(),
							AuditReason.STALE_SESSION);
					HttpApiError.AUTHENTICATION_REQUIRED.write(event.getResponse());
				}));

		http.addFilterAfter(requestPolicy, SecurityContextHolderFilter.class);
		formContentFilters.ifAvailable(filter -> http.addFilterAfter(filter, HttpRequestPolicyFilter.class));
		http.addFilterAfter(new PublicInvitationRateLimitFilter(audit, rateLimiter), HttpRequestPolicyFilter.class);
		http.addFilterBefore(new CurrentAccountSessionFilter(accountStore, audit, lifetime, mfa, rateLimiter),
				UsernamePasswordAuthenticationFilter.class);
		return http.build();
	}

	@Bean
	HttpRequestPolicyFilter httpRequestPolicyFilter(AuditService audit,
			@Value("${gestionplus.http.require-https:false}") boolean requireHttps,
			@Value("${gestionplus.http.max-body-bytes:16384}") long maxBodyBytes,
			@Value("${server.tomcat.max-parameter-count:32}") int maxParameters) {
		return new HttpRequestPolicyFilter(new HttpSecurityAudit(audit), requireHttps, maxBodyBytes, maxParameters);
	}

	@Bean
	FilterRegistrationBean<HttpRequestPolicyFilter> requestPolicyRegistration(HttpRequestPolicyFilter filter) {
		FilterRegistrationBean<HttpRequestPolicyFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "spring.mvc.formcontent.filter.enabled", matchIfMissing = true)
	FormContentFilter formContentFilter() {
		return new FormContentFilter();
	}

	@Bean
	@ConditionalOnBooleanProperty(name = "spring.mvc.formcontent.filter.enabled", matchIfMissing = true)
	FilterRegistrationBean<FormContentFilter> formContentRegistration(FormContentFilter filter) {
		FilterRegistrationBean<FormContentFilter> registration = new FilterRegistrationBean<>(filter);
		registration.setEnabled(false);
		return registration;
	}

	private Object currentPrincipal() {
		Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
		return authentication == null ? null : authentication.getPrincipal();
	}

	@Bean
	public HttpSessionEventPublisher httpSessionEventPublisher() {
		return new HttpSessionEventPublisher();
	}

	@Bean
	public CsrfTokenRepository csrfTokenRepository() {
		return new HttpSessionCsrfTokenRepository();
	}

	@Bean
	public SecurityContextRepository securityContextRepository() {

		SecurityContextRepository requestRepository = new RequestAttributeSecurityContextRepository();
		SecurityContextRepository sessionRepository = new HttpSessionSecurityContextRepository();

		return new DelegatingSecurityContextRepository(requestRepository, sessionRepository);
	}
}
