package com.dsatracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

import static org.assertj.core.api.Assertions.assertThat;

class AsyncConfigTest {

    @Test
    void backfillExecutorUsesCallerRunsPolicyWhenSaturated() {
        Executor configured = new AsyncConfig().backfillExecutor();
        ThreadPoolTaskExecutor executor = (ThreadPoolTaskExecutor) configured;
        try {
            assertThat(executor.getThreadPoolExecutor().getRejectedExecutionHandler())
                    .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        } finally {
            executor.shutdown();
        }
    }
}
