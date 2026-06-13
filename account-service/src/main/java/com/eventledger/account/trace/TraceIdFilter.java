package com.eventledger.account.trace;

import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final Pattern VALID_TRACE_ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        String traceId = resolveTraceId(request.getHeader(TraceContext.HEADER_NAME));
        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(TraceContext.HEADER_NAME, traceId);
        long startedAt = System.nanoTime();

        try {
            filterChain.doFilter(request, response);
        } finally {
            long durationMs = (System.nanoTime() - startedAt) / 1_000_000;
            logger.info(
                    "request completed method={} path={} status={} durationMs={}",
                    request.getMethod(),
                    request.getRequestURI(),
                    response.getStatus(),
                    durationMs);
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private String resolveTraceId(String suppliedTraceId) {
        if (suppliedTraceId != null && VALID_TRACE_ID.matcher(suppliedTraceId).matches()) {
            return suppliedTraceId;
        }
        return UUID.randomUUID().toString().replace("-", "").toLowerCase(Locale.ROOT);
    }
}
