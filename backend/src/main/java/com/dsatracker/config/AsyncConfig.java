package com.dsatracker.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

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

    /**
     * Bounded thread pool for onboarding backfill jobs.
     *
     * <p>Sized small on purpose: backfill is I/O-bound (external HTTP calls to
     * LeetCode/Codeforces/GFG) and low-volume (only fires once per signup), so a
     * handful of worker threads with a modest queue is plenty for the small
     * friend-group scale this app targets. {@code CallerRunsPolicy} (the default
     * when the queue saturates) provides natural back-pressure rather than
     * silently dropping a user's backfill.
     */
    @Bean(name = BACKFILL_EXECUTOR)
    public Executor backfillExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("backfill-");
        executor.initialize();
        return executor;
    }
}
