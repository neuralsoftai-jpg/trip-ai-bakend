package com.tripplanner.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * ASYNC CONFIGURATION — ThreadPoolTaskExecutor
 *
 * WHY THIS EXISTS:
 *   The dashboard endpoint fires 3 external API calls in parallel
 *   (OSRM, OpenWeatherMap, Gemini). We need a dedicated I/O-optimized
 *   thread pool — NOT the default ForkJoinPool (which is sized for CPU
 *   cores and is terrible for blocking I/O calls).
 *
 * THE THREADING MATH:
 *   If OSRM takes 800ms, Weather takes 600ms, Gemini takes 1200ms:
 *   - Sequential:  800 + 600 + 1200 = 2600ms total
 *   - Parallel:    max(800, 600, 1200) = 1200ms total  ✅ 2.2x faster
 *
 * PRODUCTION NOTE:
 *   In Java 21+, consider Virtual Threads (Project Loom) instead:
 *   spring.threads.virtual.enabled=true in application.yml.
 *   Virtual threads eliminate the need for pool tuning entirely.
 *   We keep ThreadPoolTaskExecutor here for Java 17 compatibility.
 */
@Configuration
public class AsyncConfig {

    @Value("${async.core-pool-size:10}")
    private int corePoolSize;

    @Value("${async.max-pool-size:50}")
    private int maxPoolSize;

    @Value("${async.queue-capacity:100}")
    private int queueCapacity;

    @Value("${async.thread-name-prefix:trip-async-}")
    private String threadNamePrefix;

    /**
     * Named "tripExecutor" so services can inject it explicitly via
     * @Async("tripExecutor") or pass it to CompletableFuture.supplyAsync().
     *
     * COMMON MISTAKE: Using @EnableAsync on this class — it's not needed
     * when you pass the executor explicitly to CompletableFuture.
     * Only needed if you use @Async annotations (which we do NOT, because
     * @Async has a "self-invocation" proxy bypass bug in Spring AOP).
     */
    @Bean(name = "tripExecutor")
    public Executor tripExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // Core threads always alive; max threads spawn when queue is full
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);

        // Queue holds tasks when all core threads are busy
        // Set to bounded value to prevent OutOfMemoryError under extreme load
        executor.setQueueCapacity(queueCapacity);

        // Named threads aid debugging: "trip-async-1" in stack traces
        executor.setThreadNamePrefix(threadNamePrefix);

        // On shutdown, wait for ongoing tasks to finish (graceful shutdown)
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);

        executor.initialize();
        return executor;
    }
}
