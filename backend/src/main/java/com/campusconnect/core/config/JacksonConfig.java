package com.campusconnect.core.config;

import org.bson.types.ObjectId;
import org.springframework.boot.jackson.autoconfigure.JsonMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.module.SimpleModule;
import tools.jackson.databind.ser.std.ToStringSerializer;

/** JSON as the original API wrote it: ObjectIds as hex strings, nulls included, dates as ISO-8601 strings. */
@Configuration
public class JacksonConfig {

    @Bean
    JsonMapperBuilderCustomizer campusConnectJson() {
        SimpleModule bson = new SimpleModule("campusconnect-bson");
        bson.addSerializer(ObjectId.class, ToStringSerializer.instance);
        return builder -> builder.addModule(bson);
    }
}
