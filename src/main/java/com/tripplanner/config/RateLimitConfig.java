package com.tripplanner.config;

import com.tripplanner.exception.RateLimitExceededException;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RATE LIMIT FILTER — Bucket4j IP-based Token Bucket
 *
 * WHY TOKEN BUCKET ALGORITHM:
 *   Leaky bucket: drops excess uniformly — too strict for burst use cases.
 *   Fixed window:  allows 2x spike at window boundary — not smooth.
 *   Token bucket:  allows bursts up to capacity, then smooth throttle. ✅
 *
 *   Example: User fires 10 requests instantly (burst) → all allowed.
 *   Then bucket is empty. Next 10 requests must wait 1 minute total.
 *   This is natural and fair — no penalty for short bursts.
 *
 * DISTRIBUTED RATE LIMITING NOTE:
 *   This implementation uses ConcurrentHashMap (per-JVM, in-memory).
 *   For SINGLE INSTANCE deployment: ✅ Correct.
 *   For MULTI INSTANCE (K8s, Docker Swarm): ❌ Each instance has its own
 *   bucket → user can get 10 req/min × N instances.
 *
 *   Production fix for multi-instance: Replace ConcurrentHashMap with
 *   Bucket4j's ProxyManager backed by Redis:
 *     ProxyManager<String> proxyManager = Bucket4jRedis.casBasedBuilder(redisClient).build();
 *     Bucket bucket = proxyManager.builder().addLimit(...).build(clientIP);
 *
 *   For this single-instance deployment, ConcurrentHashMap is correct.
 *
 * PROXY AWARENESS:
 *   We read X-Forwarded-For header (set by Nginx/AWS ALB/CloudFlare)
 *   to get the real client IP, not the proxy's IP.
 */
@Slf4j
@Component
public class RateLimitConfig extends OncePerRequestFilter {

    @Value("${rate-limit.capacity:10}")
    private int capacity;

    @Value("${rate-limit.refill-tokens:10}")
    private int refillTokens;

    @Value("${rate-limit.refill-period-minutes:1}")
    private int refillPeriodMinutes;

    /**
     * Per-IP bucket store. ConcurrentHashMap is thread-safe for concurrent reads.
     *
     * MEMORY CONCERN: In theory, a million unique IPs = a million bucket objects.
     * For a public app, add a TTL-based eviction (Caffeine cache) in production:
     *   Cache<String, Bucket> cache = Caffeine.newBuilder()
     *       .expireAfterAccess(1, TimeUnit.HOURS)
     *       .maximumSize(100_000)
     *       .build();
     */
    private final Map<String, Bucket> buckets = new ConcurrentHashMap<>();

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        // Skip rate limiting for actuator/health endpoints
        String path = request.getRequestURI();
        if (path.startsWith("/actuator") || path.equals("/") || path.equals("/health")) {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = extractClientIp(request);
        Bucket bucket = buckets.computeIfAbsent(clientIp, this::createNewBucket);

        if (bucket.tryConsume(1)) {
            // Token available — allow request through
            filterChain.doFilter(request, response);
        } else {
            // Bucket empty — return 429
            log.warn("Rate limit exceeded for IP: {}", clientIp);
            response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(
                """
                {
                  "status": 429,
                  "error": "Too Many Requests",
                  "message": "Rate limit exceeded. Maximum 10 requests per minute allowed.",
                  "retryAfterSeconds": 60
                }
                """
            );
        }
    }

    /**
     * Creates a new token bucket for a given IP.
     * Bandwidth = capacity tokens, refilled at rate of refillTokens per period.
     */
    private Bucket createNewBucket(String ip) {
        Refill refill = Refill.greedy(refillTokens, Duration.ofMinutes(refillPeriodMinutes));
        Bandwidth limit = Bandwidth.classic(capacity, refill);
        return Bucket.builder().addLimit(limit).build();
    }

    /**
     * Extracts real client IP, respecting reverse proxies.
     *
     * Header priority:
     *   1. X-Forwarded-For  (standard for most proxies: Nginx, AWS ALB)
     *   2. X-Real-IP        (set by some Nginx configs)
     *   3. request.getRemoteAddr() (direct connection fallback)
     *
     * SECURITY NOTE: X-Forwarded-For can be spoofed by clients if your
     * proxy doesn't sanitize it. In production, configure your proxy to
     * override/strip this header from client requests.
     */
    private String extractClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isBlank()) {
            // X-Forwarded-For can be: "clientIp, proxy1Ip, proxy2Ip"
            // The first value is the original client IP
            return xForwardedFor.split(",")[0].trim();
        }

        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isBlank()) {
            return xRealIp.trim();
        }

        return request.getRemoteAddr();
    }
}
