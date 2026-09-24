package com.ctd.demo.config;

import com.ctd.demo.error.SecurityErrors;
import com.ctd.demo.user.UserRepository;
import java.util.Arrays;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.security.web.authentication.session.*;
import org.springframework.security.core.session.SessionRegistry;
import org.springframework.security.core.session.SessionRegistryImpl;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.*;
import org.springframework.security.web.session.ConcurrentSessionFilter;
import org.springframework.security.web.session.HttpSessionEventPublisher;
import org.springframework.boot.web.servlet.ServletListenerRegistrationBean;
import org.springframework.web.cors.*;
import org.springframework.session.FindByIndexNameSessionRepository;
import org.springframework.session.Session;
import org.springframework.session.security.SpringSessionBackedSessionRegistry;

/** Configures authentication, sessions, CSRF, CORS, and protected API routes. */
@Configuration
public class SecurityConfig {
    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);
    /** Hashes and verifies passwords with BCrypt. */
    @Bean
    PasswordEncoder passwordEncoder() { return new BCryptPasswordEncoder(12); }

    /** Loads an account's credentials and enabled state for authentication. */
    @Bean
    UserDetailsService userDetailsService(UserRepository users) {
        return username -> users.findByUsername(username)
                .map(user -> User.withUsername(user.getUsername()).password(user.getPasswordHash())
                        .roles("USER").disabled(!user.isEnabled()).build())
                .orElseThrow(() -> new UsernameNotFoundException("Invalid credentials"));
    }

    /** Connects the user lookup and password encoder to Spring authentication. */
    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        var provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }

    /** Persists authenticated security contexts in HTTP sessions. */
    @Bean
    SecurityContextRepository securityContextRepository() { return new HttpSessionSecurityContextRepository(); }

    /** Tracks authenticated sessions for concurrency limits and account-wide revocation. */
    @Bean
    @Profile({"local", "test"})
    SessionRegistry localSessionRegistry() { return new SessionRegistryImpl(); }

    /** Uses the shared Spring Session store as the production session registry. */
    @Bean
    @Profile("!local & !test")
    @SuppressWarnings({"rawtypes", "unchecked"})
    SessionRegistry sharedSessionRegistry(FindByIndexNameSessionRepository<? extends Session> repository) {
        return new SpringSessionBackedSessionRegistry((FindByIndexNameSessionRepository) repository);
    }

    /** Keeps the session registry synchronized when containers destroy sessions. */
    @Bean
    @Profile({"local", "test"})
    ServletListenerRegistrationBean<HttpSessionEventPublisher> sessionEvents() {
        return new ServletListenerRegistrationBean<>(new HttpSessionEventPublisher());
    }

    /** Stores CSRF tokens in HTTP sessions. */
    @Bean
    CsrfTokenRepository csrfTokenRepository() { return new HttpSessionCsrfTokenRepository(); }

    /** Rotates the session ID and CSRF token after successful login. */
    @Bean
    SessionAuthenticationStrategy sessionAuthenticationStrategy(CsrfTokenRepository csrf, SessionRegistry sessions,
            @Value("${app.security.max-sessions-per-user:5}") int maximumSessions) {
        if (maximumSessions < 1) throw new IllegalArgumentException("Maximum sessions must be positive");
        var concurrency = new ConcurrentSessionControlAuthenticationStrategy(sessions);
        concurrency.setMaximumSessions(maximumSessions);
        concurrency.setExceptionIfMaximumExceeded(false);
        return new CompositeSessionAuthenticationStrategy(List.of(
                concurrency, new ChangeSessionIdAuthenticationStrategy(),
                new RegisterSessionAuthenticationStrategy(sessions), new CsrfAuthenticationStrategy(csrf)));
    }

    /** Allows credentialed requests from the configured frontend origins. */
    @Bean
    CorsConfigurationSource corsConfigurationSource(@Value("${app.cors.allowed-origins}") String origins) {
        var config = new CorsConfiguration();
        config.setAllowedOrigins(Arrays.stream(origins.split(",")).map(String::trim).toList());
        config.setAllowedMethods(List.of("GET", "POST", "OPTIONS"));
        config.setAllowedHeaders(List.of("Content-Type", "Accept", "X-CSRF-TOKEN"));
        config.setExposedHeaders(List.of(RequestCorrelationFilter.HEADER));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);
        var source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", config);
        return source;
    }

    /** Defines public endpoints, protected routes, error responses, and logout. */
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, SecurityContextRepository contexts,
                                           CsrfTokenRepository csrf, LoginRateLimiter loginRateLimiter,
                                           SecurityErrors securityErrors, SessionRegistry sessions, UserRepository users) throws Exception {
        return http
                .cors(Customizer.withDefaults())
                .csrf(config -> config.csrfTokenRepository(csrf))
                .securityContext(config -> config.securityContextRepository(contexts).requireExplicitSave(true))
                .requestCache(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .httpBasic(AbstractHttpConfigurer::disable)
                .headers(headers -> headers
                        .contentSecurityPolicy(csp -> csp.policyDirectives("default-src 'none'; frame-ancestors 'none'"))
                        .frameOptions(frame -> frame.deny())
                        .referrerPolicy(referrer -> referrer
                                .policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                        .permissionsPolicyHeader(policy -> policy
                                .policy("camera=(), microphone=(), geolocation=()")))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(org.springframework.http.HttpMethod.GET,
                                "/api/v1/users/csrf", "/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers(org.springframework.http.HttpMethod.POST, "/api/v1/users/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> {
                            log.debug("Authentication required for {} {}", request.getMethod(), request.getRequestURI());
                            securityErrors.write(response, 401, "UNAUTHENTICATED", "Please sign in.");
                        })
                        .accessDeniedHandler((request, response, exception) -> {
                            log.warn("Access denied for {} {}", request.getMethod(), request.getRequestURI());
                            securityErrors.write(response, 403, "ACCESS_DENIED", "Request denied. Refresh and try again.");
                        }))
                .logout(logout -> logout.logoutUrl("/api/v1/users/logout")
                        .invalidateHttpSession(true).clearAuthentication(true).deleteCookies("JSESSIONID")
                        .logoutSuccessHandler((request, response, authentication) -> {
                            log.info("Logout completed");
                            response.setStatus(204);
                        }))
                .addFilterBefore(new ConcurrentSessionFilter(sessions, event ->
                        securityErrors.write(event.getResponse(), 401, "SESSION_REVOKED",
                                "Your session is no longer active. Please sign in again.")), AuthorizationFilter.class)
                .addFilterBefore(new LoginRateLimitFilter(loginRateLimiter, securityErrors), ConcurrentSessionFilter.class)
                .addFilterBefore(new CredentialStampFilter(users, securityErrors), AuthorizationFilter.class)
                .build();
    }
}
