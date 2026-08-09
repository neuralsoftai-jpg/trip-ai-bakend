package com.tripplanner.exception;

/** Thrown by RateLimitConfig when an IP exceeds its token bucket limit */
public class RateLimitExceededException extends RuntimeException {
    public RateLimitExceededException(String ip) {
        super("Rate limit exceeded for IP: " + ip);
    }
}
