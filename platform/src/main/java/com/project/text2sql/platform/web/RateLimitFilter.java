package com.project.text2sql.platform.web;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Per-IP rate limiting for SQL execution endpoints.
 * Uses Caffeine as an in-memory sliding-window counter that expires entries
 * after one minute. When a client exceeds the configured threshold, the filter
 * returns 429 Too Many Requests with a Retry-After header.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@EnableConfigurationProperties(RateLimitProperties.class)
public class RateLimitFilter implements Filter {

    private final int maxRequests;
    private final Cache<String, AtomicInteger> counters;

    public RateLimitFilter(RateLimitProperties props) {
        this.maxRequests = Math.max(1, props.getRequestsPerMinute());
        this.counters = Caffeine.newBuilder()
                .expireAfterWrite(Duration.ofMinutes(1))
                .maximumSize(10_000)
                .build();
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        if (!(request instanceof HttpServletRequest httpReq)
                || !(response instanceof HttpServletResponse httpRes)) {
            chain.doFilter(request, response);
            return;
        }

        String path = httpReq.getRequestURI();
        if (!isRateLimited(path)) {
            chain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(httpReq);
        AtomicInteger counter = counters.get(clientIp, k -> new AtomicInteger(0));

        if (counter.incrementAndGet() > maxRequests) {
            httpRes.setStatus(429);
            httpRes.setHeader("Retry-After", "60");
            httpRes.setContentType("application/json");
            httpRes.getWriter().write(
                    "{\"error\":\"RATE_LIMITED\",\"message\":\"Too many requests. Limit: "
                            + maxRequests + " per minute.\"}");
            return;
        }

        chain.doFilter(request, response);
    }

    private boolean isRateLimited(String path) {
        return path.startsWith("/api/executor") || path.startsWith("/api/text2sql");
    }

    private String resolveClientIp(HttpServletRequest req) {
        String forwarded = req.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return req.getRemoteAddr();
    }
}
