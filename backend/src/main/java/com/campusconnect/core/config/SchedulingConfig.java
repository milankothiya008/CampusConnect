package com.campusconnect.core.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.task.SimpleAsyncTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.SimpleAsyncTaskScheduler;

/**
 * Scheduled sweepers (reminders, waitlists, elections, story expiry, ...). Each one is annotated
 * {@code @SchedulerLock}, so with several replicas exactly one runs each tick (lock kept in Redis).
 * Tests switch scheduling off and call the sweepers directly.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT5M")
@ConditionalOnProperty(name = "app.scheduling.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

    @Bean
    LockProvider lockProvider(RedisConnectionFactory connectionFactory) {
        return new RedisLockProvider(connectionFactory, "campusconnect");
    }

    /**
     * The sweepers' scheduler, built the way Boot builds its default (virtual threads, spring.task.scheduling.*).
     * It has to be declared: the WebSocket broker brings its own TaskSchedulers, so Boot skips the default and
     * {@code @Scheduled} would otherwise run every sweeper on one fallback thread.
     */
    @Bean(name = "taskScheduler")
    SimpleAsyncTaskScheduler taskScheduler(SimpleAsyncTaskSchedulerBuilder builder) {
        return builder.threadNamePrefix("sweeper-").build();
    }
}
