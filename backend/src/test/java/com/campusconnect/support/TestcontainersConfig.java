package com.campusconnect.support;

import com.redis.testcontainers.RedisContainer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.mongodb.MongoDBContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Real MongoDB 8 (single-node replica set, so transactions work) and Redis 8 in Docker for the integration
 * tests. Spring starts them once per test context and points the app at them ({@code @ServiceConnection}).
 */
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfig {

    @Bean
    @ServiceConnection
    MongoDBContainer mongoContainer() {
        // A replica set (Testcontainers 2 no longer starts one by default): multi-document transactions need it.
        return new MongoDBContainer(DockerImageName.parse("mongo:8.0")).withReplicaSet();
    }

    @Bean
    @ServiceConnection
    RedisContainer redisContainer() {
        return new RedisContainer(DockerImageName.parse("redis:8.2-alpine"));
    }
}
