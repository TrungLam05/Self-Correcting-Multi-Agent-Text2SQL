package com.project.text2sql.platform.web;

import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class RateLimitFilterTest {

    private RateLimitFilter filter;
    private static final int LIMIT = 5;

    @BeforeEach
    void setUp() {
        RateLimitProperties props = new RateLimitProperties();
        props.setRequestsPerMinute(LIMIT);
        filter = new RateLimitFilter(props);
    }

    @Test
    void allowsRequestsWithinLimit() throws Exception {
        for (int i = 0; i < LIMIT; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
            req.setRemoteAddr("10.0.0.1");
            MockHttpServletResponse res = new MockHttpServletResponse();

            AtomicInteger reached = new AtomicInteger(0);
            FilterChain chain = (r, s) -> reached.incrementAndGet();

            filter.doFilter(req, res, chain);
            assertEquals(1, reached.get(), "Request " + (i + 1) + " should pass through");
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    void rejects429AfterBurst() throws Exception {
        // Exhaust the limit
        for (int i = 0; i < LIMIT; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
            req.setRemoteAddr("10.0.0.2");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (r, s) -> {});
        }

        // Next request should be rejected
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
        req.setRemoteAddr("10.0.0.2");
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicInteger reached = new AtomicInteger(0);
        filter.doFilter(req, res, (r, s) -> reached.incrementAndGet());

        assertEquals(0, reached.get(), "Request should NOT reach the controller");
        assertEquals(429, res.getStatus());
        assertEquals("60", res.getHeader("Retry-After"));
        assertTrue(res.getContentAsString().contains("RATE_LIMITED"));
    }

    @Test
    void differentIpsHaveSeparateLimits() throws Exception {
        // Exhaust limit for IP A
        for (int i = 0; i < LIMIT; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/text2sql/execute");
            req.setRemoteAddr("10.0.0.3");
            filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});
        }

        // IP B should still be allowed
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/text2sql/execute");
        req.setRemoteAddr("10.0.0.4");
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicInteger reached = new AtomicInteger(0);
        filter.doFilter(req, res, (r, s) -> reached.incrementAndGet());

        assertEquals(1, reached.get());
        assertEquals(200, res.getStatus());
    }

    @Test
    void doesNotLimitSchemaEndpoints() throws Exception {
        for (int i = 0; i < LIMIT + 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/schema");
            req.setRemoteAddr("10.0.0.5");
            MockHttpServletResponse res = new MockHttpServletResponse();

            AtomicInteger reached = new AtomicInteger(0);
            filter.doFilter(req, res, (r, s) -> reached.incrementAndGet());

            assertEquals(1, reached.get(), "Schema requests should never be rate-limited");
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    void respectsXForwardedForHeader() throws Exception {
        // Exhaust limit using X-Forwarded-For IP
        for (int i = 0; i < LIMIT; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
            req.setRemoteAddr("127.0.0.1");
            req.addHeader("X-Forwarded-For", "203.0.113.50, 70.41.3.18");
            filter.doFilter(req, new MockHttpServletResponse(), (r, s) -> {});
        }

        // Same forwarded IP should now be blocked
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
        req.setRemoteAddr("127.0.0.1");
        req.addHeader("X-Forwarded-For", "203.0.113.50");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, (r, s) -> {});
        assertEquals(429, res.getStatus());
    }
}
