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
