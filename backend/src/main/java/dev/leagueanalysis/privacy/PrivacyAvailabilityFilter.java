package dev.leagueanalysis.privacy;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;

/** Fail closed after loss of the process lifetime lease, including cached responses. */
public final class PrivacyAvailabilityFilter implements Filter {
    private final PrivacyRuntimeGuard guard;
    public PrivacyAvailabilityFilter(PrivacyRuntimeGuard guard) { this.guard=guard; }
    @Override public void doFilter(ServletRequest request,ServletResponse response,FilterChain chain) throws IOException,ServletException {
        try { guard.requireHealthy(); }
        catch(IllegalStateException unavailable) {
            var http=(HttpServletResponse)response;
            http.setStatus(503); http.setHeader("Cache-Control","no-store");
            http.setContentType("application/json"); http.getWriter().write("{\"error\":\"SERVICE_UNAVAILABLE\"}");
            return;
        }
        chain.doFilter(request,response);
    }
}
