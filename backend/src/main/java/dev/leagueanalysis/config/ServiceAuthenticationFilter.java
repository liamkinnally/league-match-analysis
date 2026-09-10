package dev.leagueanalysis.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public final class ServiceAuthenticationFilter implements Filter {
    private static final String UNAUTHORIZED_BODY = "{\"error\":\"UNAUTHORIZED\"}";

    private final boolean required;
    private final byte[] expectedAuthorization;

    public ServiceAuthenticationFilter(
            @Value("${league-analysis.service-auth.required:false}") boolean required,
            @Value("${league-analysis.service-auth.token:}") String token) {
        this.required = required;
        if (!token.isEmpty() && !validToken(token)) {
            throw new IllegalStateException("BACKEND_SERVICE_TOKEN must contain at least 32 printable non-whitespace ASCII characters");
        }
        if (required && token.isEmpty()) {
            throw new IllegalStateException("BACKEND_SERVICE_TOKEN is required when BACKEND_AUTH_REQUIRED is true");
        }
        this.expectedAuthorization = token.isEmpty()
                ? new byte[0]
                : ("Bearer " + token).getBytes(StandardCharsets.US_ASCII);
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {
        var httpRequest = (HttpServletRequest) request;
        var httpResponse = (HttpServletResponse) response;
        if (!required || healthRequest(httpRequest) || authenticated(httpRequest)) {
            chain.doFilter(request, response);
            return;
        }
        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setHeader("Cache-Control", "no-store");
        httpResponse.setContentType("application/json");
        httpResponse.setCharacterEncoding(StandardCharsets.UTF_8.name());
        httpResponse.getWriter().write(UNAUTHORIZED_BODY);
    }

    private boolean authenticated(HttpServletRequest request) {
        var supplied = request.getHeader("Authorization");
        var suppliedBytes = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expectedAuthorization, suppliedBytes);
    }

    private static boolean healthRequest(HttpServletRequest request) {
        return (request.getMethod().equals("GET") || request.getMethod().equals("HEAD"))
                && request.getRequestURI().equals("/actuator/health")
                && request.getQueryString() == null;
    }

    private static boolean validToken(String token) {
        if (token.length() < 32) return false;
        for (int index = 0; index < token.length(); index++) {
            char character = token.charAt(index);
            if (character < 0x21 || character > 0x7e) return false;
        }
        return true;
    }
}
