package com.dsatracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Enables Spring's asynchronous method execution and defines the executor used
 * for the one-time onboarding backfill job (see design.md "Backfill (onboarding)
 * logic" and Requirement 1.4).
 *
 * <p>{@code @EnableScheduling} already lives on {@link com.dsatracker.BackendApplication};
 * this class adds {@link EnableAsync} so methods annotated {@code @Async} run off
 * the request thread. That matters for backfill: user creation
 * ({@code POST /api/users}, task 8.1) must return immediately while the
 * potentially slow, multi-platform history import runs in the background.
 *
 * <p>The dedicated {@link #backfillExecutor()} bean isolates backfill work onto
 * its own bounded thread pool so a burst of signups cannot starve other async
 * work (or vice-versa). It is referenced by name from
 * {@code @Async("backfillExecutor")} in
 * {@link com.dsatracker.service.BackfillService}.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    /** Bean name used by {@code @Async("backfillExecutor")}. */
    public static final String BACKFILL_EXECUTOR = "backfillExecutor";
    public static final String CATALOG_EXECUTOR = "catalogExecutor";

    @Bean(name = CATALOG_EXECUTOR)
    public Executor catalogExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(1);
        executor.setThreadNamePrefix("catalog-sync-");
        // Never silently lose a scheduled refresh if startup work is still queued.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }

    /**
     * Bounded thread pool for onboarding backfill jobs.
     *
     * <p>Sized small on purpose: backfill is I/O-bound (external HTTP calls to
     * LeetCode/Codeforces/GFG) and low-volume (only fires once per signup), so a
     * handful of worker threads with a modest queue is plenty for the small
     * friend-group scale this app targets. {@code CallerRunsPolicy} is configured
     * explicitly so saturation applies back-pressure instead of rejecting and
     * losing a user's backfill.
     */
    @Bean(name = BACKFILL_EXECUTOR)
    public Executor backfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("backfill-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
