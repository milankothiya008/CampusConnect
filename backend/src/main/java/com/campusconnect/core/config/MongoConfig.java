package com.campusconnect.core.config;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.boot.mongodb.autoconfigure.MongoClientSettingsBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.config.EnableMongoAuditing;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * MongoDB: auditing ({@code createdAt}/{@code updatedAt} like Mongoose timestamps), multi-document transactions
 * (replica set required) and a connection pool sized for one replica of the API.
 */
@Configuration
@EnableMongoAuditing
public class MongoConfig {

    /**
     * Documents are stored exactly as the original Mongoose app stored them: no {@code _class} type hints (the
     * model has no polymorphic fields, so every value's type is known from its declaration).
     */
    @Bean
    static BeanPostProcessor noTypeHints() {
        return new BeanPostProcessor() {
            @Override
            public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof MappingMongoConverter converter) {
                    converter.setTypeMapper(new DefaultMongoTypeMapper(null));
                }
                return bean;
            }
        };
    }

    @Bean
    MongoTransactionManager transactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory);
    }

    /** For the few flows that must change several documents together (see docs/HLD.md). */
    @Bean
    TransactionTemplate transactionTemplate(MongoTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    @Bean
    MongoClientSettingsBuilderCustomizer connectionPool(
            @Value("${app.mongo.max-pool-size:100}") int maxPoolSize,
            @Value("${app.mongo.min-pool-size:10}") int minPoolSize) {
        return settings -> settings
                .applyToConnectionPoolSettings(pool -> pool
                        .maxSize(maxPoolSize)
                        .minSize(minPoolSize)
                        .maxConnecting(8)
                        .maxWaitTime(5, TimeUnit.SECONDS)
                        .maxConnectionIdleTime(5, TimeUnit.MINUTES))
                .applyToSocketSettings(socket -> socket.connectTimeout(5, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS))
                .applyToClusterSettings(cluster -> cluster.serverSelectionTimeout(10, TimeUnit.SECONDS));
    }
}
