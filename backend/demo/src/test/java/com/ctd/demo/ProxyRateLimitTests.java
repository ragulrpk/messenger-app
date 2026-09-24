package com.ctd.demo;

import com.jayway.jsonpath.JsonPath;
import java.net.CookieManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import static org.assertj.core.api.Assertions.assertThat;

abstract class ProxyRateLimitSupport {
    @Value("${local.server.port}") int port;

    // Real HTTP exercises Tomcat's RemoteIpValve, which MockMvc bypasses.
    void checkLimit(boolean trusted) throws Exception {
        try (var client = HttpClient.newBuilder().cookieHandler(new CookieManager()).build()) {
            String base = "http://127.0.0.1:" + port + "/api/v1/users/";
            var csrf = client.send(HttpRequest.newBuilder(URI.create(base + "csrf")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertThat(csrf.statusCode()).isEqualTo(200);
            String token = JsonPath.read(csrf.body(), "$.token");
            for (int i = 0; i < 21; i++) {
                // Untrusted peers must not evade limits by changing the header.
                String address = trusted ? "192.0.2.1" : "192.0.2." + (i + 1);
                assertThat(login(client, base, token, address)).isEqualTo(i < 20 ? 400 : 429);
            }
            // A second client behind the same trusted proxy has its own bucket.
            assertThat(login(client, base, token, "192.0.2.100")).isEqualTo(trusted ? 400 : 429);
        }
    }

    private int login(HttpClient client, String base, String token, String address) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "login"))
                .header("X-Forwarded-For", address)
                .header("X-CSRF-TOKEN", token)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{}"))
                .build(), HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.tomcat.remoteip.internal-proxies=127[.]0[.]0[.]1"})
@ActiveProfiles("test")
class ProxyRateLimitTests extends ProxyRateLimitSupport {
    @Test
    void trustedProxyPreservesSeparateClientLimits() throws Exception { checkLimit(true); }
}

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"server.address=127.0.0.1", "server.tomcat.remoteip.internal-proxies=^$"})
@ActiveProfiles("test")
class UntrustedProxyRateLimitTests extends ProxyRateLimitSupport {
    @Test
    void untrustedForwardedHeadersCannotBypassLimit() throws Exception { checkLimit(false); }
}
