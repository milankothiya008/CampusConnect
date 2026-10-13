package com.campusconnect.core.config;

import com.campusconnect.core.ratelimit.RateLimitInterceptor;
import com.campusconnect.core.security.AccessPolicyInterceptor;
import com.campusconnect.core.util.CampusTime;
import io.github.bucket4j.distributed.proxy.ProxyManager;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.CacheControl;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Interceptors (rate limits first, then access rules) and the local uploads folder at /uploads. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final ProxyManager<byte[]> buckets;
    private final AppProperties properties;

    public WebMvcConfig(ProxyManager<byte[]> buckets, AppProperties properties) {
        this.buckets = buckets;
        this.properties = properties;
        CampusTime.configure(properties.getUniversity().getTimezoneOffset());
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new RateLimitInterceptor(buckets, properties)).addPathPatterns("/api/**").order(0);
        registry.addInterceptor(new AccessPolicyInterceptor()).addPathPatterns("/api/**").order(1);
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // File names are unique, so local uploads can be cached for good.
        String location = Path.of(properties.getUploads().getDir()).toAbsolutePath().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(location.endsWith("/") ? location : location + "/")
                .setCacheControl(CacheControl.maxAge(7, TimeUnit.DAYS).cachePublic().immutable());
    }
}
