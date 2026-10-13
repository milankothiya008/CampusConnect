package com.campusconnect.core.ratelimit;

import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import io.github.bucket4j.redis.lettuce.Bucket4jLettuce;
import io.lettuce.core.RedisClient;
import java.time.Duration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Bucket4j buckets stored in Redis; idle buckets expire once they would be full again. */
@Configuration
public class RateLimitConfig {

    @Bean
    ProxyManager<byte[]> rateLimitBuckets(RedisClient bucketRedisClient) {
        return Bucket4jLettuce.casBasedBuilder(bucketRedisClient)
                .expirationAfterWrite(ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ofSeconds(30)))
                .requestTimeout(Duration.ofSeconds(1))
                .build();
    }
}
