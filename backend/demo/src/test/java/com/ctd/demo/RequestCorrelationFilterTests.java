package com.ctd.demo;

import com.ctd.demo.config.RequestCorrelationFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class RequestCorrelationFilterTests {
    @Test
    void acceptsRequestIdsOnlyFromConfiguredProxyPeers() throws Exception {
        var filter = new RequestCorrelationFilter("10[.]0[.]0[.]1");
        var untrusted = new MockHttpServletRequest();
        untrusted.setRemoteAddr("203.0.113.10");
        untrusted.addHeader(RequestCorrelationFilter.HEADER, "external-request-id");
        var untrustedResponse = new MockHttpServletResponse();
        filter.doFilter(untrusted, untrustedResponse, new MockFilterChain());
        assertThat(untrustedResponse.getHeader(RequestCorrelationFilter.HEADER))
                .isNotEqualTo("external-request-id");

        var trusted = new MockHttpServletRequest();
        trusted.setRemoteAddr("192.0.2.20");
        trusted.setAttribute("org.apache.catalina.AccessLog.RemoteAddr", "10.0.0.1");
        trusted.addHeader(RequestCorrelationFilter.HEADER, "proxy-request-id");
        var trustedResponse = new MockHttpServletResponse();
        filter.doFilter(trusted, trustedResponse, new MockFilterChain());
        assertThat(trustedResponse.getHeader(RequestCorrelationFilter.HEADER))
                .isEqualTo("proxy-request-id");
    }
}
