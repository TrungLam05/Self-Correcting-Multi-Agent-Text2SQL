package com.project.text2sql.platform.web;

import java.io.IOException;
import java.util.concurrent.Semaphore;

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
 * Limits concurrent SQL query execution to protect the database connection pool.
 * When all permits are taken, returns 503 Service Unavailable with a Retry-After
 * header so clients can back off.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
@EnableConfigurationProperties(ConcurrencyProperties.class)
public class ConcurrencyLimitFilter implements Filter {

    private final Semaphore semaphore;
    private final int maxConcurrent;

    public ConcurrencyLimitFilter(ConcurrencyProperties props) {
        this.maxConcurrent = Math.max(1, props.getMaxConcurrentQueries());
        this.semaphore = new Semaphore(this.maxConcurrent);
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
        if (!isConcurrencyLimited(path)) {
            chain.doFilter(request, response);
            return;
        }

        if (!semaphore.tryAcquire()) {
            httpRes.setStatus(503);
            httpRes.setHeader("Retry-After", "5");
            httpRes.setContentType("application/json");
            httpRes.getWriter().write(
                    "{\"error\":\"SERVICE_BUSY\",\"message\":\"Too many concurrent queries. Max: "
                            + maxConcurrent + ". Try again shortly.\"}");
            return;
        }

        try {
            chain.doFilter(request, response);
        } finally {
            semaphore.release();
        }
    }

    private boolean isConcurrencyLimited(String path) {
        return path.startsWith("/api/executor") || path.startsWith("/api/text2sql");
    }
}
