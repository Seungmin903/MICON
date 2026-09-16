package com.micon.backend.common.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RequestAccessLogFilter extends OncePerRequestFilter {
    public static final String HEADER = "X-Request-ID";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        var previous = MDC.getCopyOfContextMap();
        String incoming = request.getHeader(HEADER);
        String traceId = incoming != null && UUID_PATTERN.matcher(incoming).matches()
                ? incoming : UUID.randomUUID().toString();
        MDC.put(AccessLog.TRACE_ID, traceId);
        boolean probe = request.getRequestURI().startsWith("/actuator/");
        MDC.put(AccessLog.PROBE, Boolean.toString(probe));
        response.setHeader(HEADER, traceId);
        long started = System.nanoTime();
        Throwable failure = null;
        try {
            chain.doFilter(request, response);
        } catch (IOException | ServletException | RuntimeException exception) {
            failure = exception;
            throw exception;
        } finally {
            // Only the controller template is logged; raw paths can contain personal data or tokens.
            Object route = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
            int status = failure == null ? response.getStatus() : 500;
            AccessLog.write("http_access", status >= 400, probe,
                    "method", request.getMethod(), "route", route == null ? "unmatched" : route,
                    "status", status, "duration_ms", AccessLog.elapsed(started),
                    "error", AccessLog.errorType(failure));
            if (previous == null) MDC.clear();
            else MDC.setContextMap(previous);
        }
    }
}
