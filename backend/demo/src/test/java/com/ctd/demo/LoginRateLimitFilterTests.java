package com.ctd.demo;

import com.ctd.demo.config.LoginRateLimitFilter;
import com.ctd.demo.config.InMemoryLoginRateLimiter;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.assertThat;

class LoginRateLimitFilterTests {
    private static LoginRateLimitFilter filter() {
        return new LoginRateLimitFilter(new InMemoryLoginRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                new com.ctd.demo.error.SecurityErrors(new ObjectMapper()));
    }

    private static int attempt(LoginRateLimitFilter filter, int outcome) throws Exception {
        var request = new MockHttpServletRequest("POST", "/api/v1/users/login");
        request.setServletPath("/api/v1/users/login");
        request.setRemoteAddr("127.0.0.1");
        var response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> ((HttpServletResponse) res).setStatus(outcome));
        return response.getStatus();
    }

    @Test
    void successfulLoginsDoNotResetFailuresAlreadyCounted() throws Exception {
        var filter = filter();
        // An attacker with one real account tries 19 guesses, then logs in to themselves, and repeats.
        for (int i = 0; i < 19; i++) assertThat(attempt(filter, 401)).isEqualTo(401);
        assertThat(attempt(filter, 200)).isEqualTo(200);
        assertThat(attempt(filter, 401)).isEqualTo(401);
        // 20 failures have been counted in this window: the login no longer bought more guesses.
        assertThat(attempt(filter, 401)).isEqualTo(429);
        assertThat(attempt(filter, 200)).isEqualTo(429);
    }

    @Test
    void successfulLoginsFromOneAddressAreNeverLimited() throws Exception {
        var filter = filter();
        // Many colleagues signing in from one office address cost nothing.
        for (int i = 0; i < 100; i++) assertThat(attempt(filter, 200)).isEqualTo(200);
        for (int i = 0; i < 20; i++) assertThat(attempt(filter, 401)).isEqualTo(401);
        assertThat(attempt(filter, 401)).isEqualTo(429);
    }

    @Test
    void limitsOneClientWithoutBlockingAnother() throws Exception {
        var limiter = new LoginRateLimitFilter(
                new InMemoryLoginRateLimiter(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)),
                new com.ctd.demo.error.SecurityErrors(new ObjectMapper()));
        for (int i = 0; i < 21; i++) {
            var request = new MockHttpServletRequest("POST", "/api/v1/users/login");
            request.setServletPath("/api/v1/users/login");
            request.setRemoteAddr("127.0.0.1");
            var response = new MockHttpServletResponse();
            limiter.doFilter(request, response, (req, res) ->
                    ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_UNAUTHORIZED));
            assertThat(response.getStatus()).isEqualTo(i < 20 ? 401 : 429);
        }
        var request = new MockHttpServletRequest("POST", "/api/v1/users/login");
        request.setServletPath("/api/v1/users/login");
        request.setRemoteAddr("127.0.0.2");
        var response = new MockHttpServletResponse();
        limiter.doFilter(request, response, (req, res) ->
                ((HttpServletResponse) res).setStatus(HttpServletResponse.SC_UNAUTHORIZED));
        assertThat(response.getStatus()).isEqualTo(401);
    }
}
