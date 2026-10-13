package com.campusconnect.core.config;

import com.campusconnect.core.cache.CacheEvictionBus;
import com.campusconnect.core.cache.CacheNames;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.data.redis.autoconfigure.DataRedisConnectionDetails;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.LoggingCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/** Redis: shared Spring caches, the cache-eviction channel and a raw Lettuce client for Bucket4j. */
@Configuration
@EnableCaching
public class RedisConfig implements CachingConfigurer {

    /** A Redis problem during a cache read or write is logged and treated as a miss — it never fails the request. */
    @Override
    public CacheErrorHandler errorHandler() {
        return new LoggingCacheErrorHandler();
    }

    /**
     * The shared caches live in their own Redis database (app.redis.cache-database, default 1). Clearing a cache
     * SCANs for its keys; in the main database that would walk every rate-limit bucket, presence entry and e-mail
     * dedupe key on each event write. Kept off the bean list so Boot's own RedisConnectionFactory stays the default.
     */
    static final class CacheConnection implements DisposableBean {

        final LettuceConnectionFactory factory;

        CacheConnection(DataRedisConnectionDetails details, int database, boolean ssl, Duration timeout) {
            DataRedisConnectionDetails.Standalone server = details.getStandalone();
            RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(server.getHost(), server.getPort());
            standalone.setDatabase(database);
            if (details.getUsername() != null && !details.getUsername().isBlank()) {
                standalone.setUsername(details.getUsername());
            }
            if (details.getPassword() != null && !details.getPassword().isBlank()) {
                standalone.setPassword(details.getPassword());
            }
            LettuceClientConfiguration.LettuceClientConfigurationBuilder client = LettuceClientConfiguration.builder().commandTimeout(timeout);
            if (ssl) {
                client.useSsl();
            }
            this.factory = new LettuceConnectionFactory(standalone, client.build());
            this.factory.afterPropertiesSet();
            this.factory.start();
        }

        @Override
        public void destroy() {
            factory.destroy();
        }
    }

    @Bean
    CacheConnection cacheConnection(DataRedisConnectionDetails details,
            @Value("${app.redis.cache-database:1}") int database,
            @Value("${spring.data.redis.ssl.enabled:false}") boolean ssl,
            @Value("${spring.data.redis.timeout:2s}") Duration timeout) {
        return new CacheConnection(details, database, ssl, timeout);
    }

    @Bean
    RedisCacheManager cacheManager(CacheConnection cacheConnection) {
        // Default typing restricted to our own types and JDK value types (no gadget classes).
        var types = BasicPolymorphicTypeValidator.builder()
                .allowIfSubType("com.campusconnect.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.time.")
                .allowIfSubType("java.lang.")
                .build();
        var serializer = GenericJacksonJsonRedisSerializer.builder().enableDefaultTyping(types).enableSpringCacheNullValueSupport().build();
        RedisCacheConfiguration defaults = RedisCacheConfiguration.defaultCacheConfig()
                .prefixCacheNameWith("cc:cache:")
                .entryTtl(Duration.ofSeconds(30))
                .disableCachingNullValues()
                .serializeValuesWith(RedisSerializationContext.SerializationPair.fromSerializer(serializer));
        Map<String, RedisCacheConfiguration> perCache = new HashMap<>();
        CacheNames.TTL.forEach((name, ttl) -> perCache.put(name, defaults.entryTtl(ttl)));
        // SCAN-based clearing (never KEYS) over the small cache database. Immediate writes: with Lettuce the writer
        // otherwise puts and evicts asynchronously, so a read right after a write could still get the evicted page.
        RedisCacheWriter writer = RedisCacheWriter.create(cacheConnection.factory, config -> config.batchStrategy(BatchStrategies.scan(1000)).immediateWrites());
        return RedisCacheManager.builder(writer).cacheDefaults(defaults).withInitialCacheConfigurations(perCache).transactionAware().build();
    }

    @Bean
    RedisMessageListenerContainer redisListenerContainer(RedisConnectionFactory connectionFactory, CacheEvictionBus evictionBus) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(evictionBus, new ChannelTopic(CacheEvictionBus.CHANNEL));
        return container;
    }

    /** A plain Lettuce client (Bucket4j's distributed buckets talk to Redis directly), same server as Spring Data Redis. */
    @Bean(destroyMethod = "shutdown")
    RedisClient bucketRedisClient(DataRedisConnectionDetails details, @Value("${spring.data.redis.ssl.enabled:false}") boolean ssl) {
        DataRedisConnectionDetails.Standalone server = details.getStandalone();
        RedisURI.Builder uri = RedisURI.builder()
                .withHost(server.getHost())
                .withPort(server.getPort())
                .withDatabase(server.getDatabase())
                .withSsl(ssl)
                .withTimeout(Duration.ofSeconds(2));
        String password = details.getPassword();
        if (password != null && !password.isBlank()) {
            if (details.getUsername() != null && !details.getUsername().isBlank()) {
                uri.withAuthentication(details.getUsername(), password.toCharArray());
            } else {
                uri.withPassword(password.toCharArray());
            }
        }
        return RedisClient.create(uri.build());
    }
}
