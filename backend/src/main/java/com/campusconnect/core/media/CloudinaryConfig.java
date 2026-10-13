package com.campusconnect.core.media;

import com.campusconnect.core.config.AppProperties;
import com.cloudinary.Cloudinary;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** The Cloudinary SDK client (server-side uploads and deletions); unused when Cloudinary is not configured. */
@Configuration
public class CloudinaryConfig {

    @Bean
    Cloudinary cloudinary(AppProperties properties) {
        AppProperties.Cloudinary config = properties.getCloudinary();
        return new Cloudinary(Map.of(
                "cloud_name", config.getCloudName(),
                "api_key", config.getApiKey(),
                "api_secret", config.getApiSecret(),
                "secure", true));
    }
}
