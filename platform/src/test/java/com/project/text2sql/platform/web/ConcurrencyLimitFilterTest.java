package com.project.text2sql.platform.web;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class ConcurrencyLimitFilterTest {

    private ConcurrencyLimitFilter filter;
    private static final int MAX_CONCURRENT = 2;

    @BeforeEach
    void setUp() {
        ConcurrencyProperties props = new ConcurrencyProperties();
        props.setMaxConcurrentQueries(MAX_CONCURRENT);
        filter = new ConcurrencyLimitFilter(props);
    }

    @Test
    void allowsRequestsWithinConcurrencyLimit() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
        MockHttpServletResponse res = new MockHttpServletResponse();

        AtomicInteger reached = new AtomicInteger(0);
        filter.doFilter(req, res, (r, s) -> reached.incrementAndGet());

        assertEquals(1, reached.get());
        assertEquals(200, res.getStatus());
    }

    @Test
    void rejects503WhenAllPermitsTaken() throws Exception {
        CountDownLatch holdOpen = new CountDownLatch(1);
        CountDownLatch slotsOccupied = new CountDownLatch(MAX_CONCURRENT);
        AtomicInteger rejections = new AtomicInteger(0);

        ExecutorService pool = Executors.newFixedThreadPool(MAX_CONCURRENT + 1);

        // Occupy all permits with long-running requests
        for (int i = 0; i < MAX_CONCURRENT; i++) {
            pool.submit(() -> {
                try {
                    MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
                    MockHttpServletResponse res = new MockHttpServletResponse();
                    FilterChain blocking = (r, s) -> {
                        slotsOccupied.countDown();
                        try { holdOpen.await(); } catch (InterruptedException e) { throw new RuntimeException(e); }
                    };
                    filter.doFilter(req, res, blocking);
                } catch (Exception ignored) {}
            });
        }

        // Wait until all slots are occupied
        assertTrue(slotsOccupied.await(5, TimeUnit.SECONDS), "Slots should be occupied within timeout");

        // Try one more -- should be rejected
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
        MockHttpServletResponse res = new MockHttpServletResponse();
        filter.doFilter(req, res, (r, s) -> rejections.decrementAndGet());

        assertEquals(503, res.getStatus());
        assertEquals("5", res.getHeader("Retry-After"));
        assertTrue(res.getContentAsString().contains("SERVICE_BUSY"));
        assertEquals(0, rejections.get(), "Chain should NOT have been called");

        // Release blocked requests
        holdOpen.countDown();
        pool.shutdown();
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void releasesPermitAfterCompletion() throws Exception {
        // Use all permits and release them
        for (int i = 0; i < MAX_CONCURRENT; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/text2sql/execute");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (r, s) -> {});
            assertEquals(200, res.getStatus());
        }

        // Should still work after permits are released
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/text2sql/execute");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger reached = new AtomicInteger(0);
        filter.doFilter(req, res, (r, s) -> reached.incrementAndGet());

        assertEquals(1, reached.get());
        assertEquals(200, res.getStatus());
    }

    @Test
    void doesNotLimitSchemaEndpoints() throws Exception {
        // Even if executor is at capacity, schema should work
        // (We can't easily block permits here without threads, so just verify schema passes)
        for (int i = 0; i < MAX_CONCURRENT + 5; i++) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/schema");
            MockHttpServletResponse res = new MockHttpServletResponse();

            AtomicInteger reached = new AtomicInteger(0);
            filter.doFilter(req, res, (r, s) -> reached.incrementAndGet());

            assertEquals(1, reached.get(), "Schema requests should never be concurrency-limited");
            assertEquals(200, res.getStatus());
        }
    }

    @Test
    void releasesPermitEvenOnException() throws Exception {
        // Force an exception in the chain
        try {
            MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
            MockHttpServletResponse res = new MockHttpServletResponse();
            filter.doFilter(req, res, (r, s) -> { throw new RuntimeException("boom"); });
        } catch (RuntimeException ignored) {}

        // Permit should have been released -- next request should succeed
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/executor/execute");
        MockHttpServletResponse res = new MockHttpServletResponse();
        AtomicInteger reached = new AtomicInteger(0);
        filter.doFilter(req, res, (r, s) -> reached.incrementAndGet());

        assertEquals(1, reached.get());
        assertEquals(200, res.getStatus());
    }
}
