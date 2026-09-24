package com.ctd.demo.error;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** Writes fixed JSON error responses from security filters outside MVC. */
@Component
public final class SecurityErrors {
    private final ObjectMapper json;

    public SecurityErrors(ObjectMapper json) { this.json = json; }

    /** Sends a status and constant error values without including request content. */
    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getWriter(), Map.of("code", code, "message", message));
    }
}
