package com.onfinapp.api.common;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import io.opentelemetry.api.trace.Span;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Gives every request a correlation ID and writes one log line when it finishes.
 *
 * <p>The ID comes from the X-Correlation-ID request header. When the header is missing or is not a plain
 * token (letters, digits, dot, underscore, hyphen; at most 64 characters), a new UUID is used instead, so
 * arbitrary client text never reaches the logs. The ID is returned in the same response header, added to
 * every log line written during the request, and recorded on the request's trace span.
 *
 * <p>Runs before Spring Security, so rejected requests carry the ID too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String LOG_PROPERTY = "correlationId";
    public static final String SPAN_ATTRIBUTE = "app.correlation_id";

    private static final Logger log = LoggerFactory.getLogger(CorrelationFilter.class);
    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String accepted = accept(request.getHeader(HEADER));
        String correlationId = accepted != null ? accepted : UUID.randomUUID().toString();

        response.setHeader(HEADER, correlationId);
        Span.current().setAttribute(SPAN_ATTRIBUTE, correlationId);
        MDC.put(LOG_PROPERTY, correlationId);
        long started = System.nanoTime();
        try {
            chain.doFilter(request, response);
        } finally {
            logRequest(request, response, (System.nanoTime() - started) / 1_000_000);
            MDC.remove(LOG_PROPERTY);
        }
    }

    /** The header value if it is safe to log, otherwise null. */
    public static String accept(String value) {
        return value != null && SAFE_TOKEN.matcher(value).matches() ? value : null;
    }

    private static void logRequest(HttpServletRequest request, HttpServletResponse response, long elapsedMs) {
        String message = "{} {} responded {} in {} ms";
        Object[] values = {request.getMethod(), request.getRequestURI(), response.getStatus(), elapsedMs};
        if (response.getStatus() >= 500) {
            log.warn(message, values);
        } else if (request.getRequestURI().startsWith("/api/health")) {
            log.debug(message, values);
        } else {
            log.info(message, values);
        }
    }
}
