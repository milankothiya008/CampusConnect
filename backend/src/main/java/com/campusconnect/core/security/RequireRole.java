package com.campusconnect.core.security;

import com.campusconnect.domain.user.GlobalRole;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Only these global roles may call the route ({@code restrictTo(...)}). */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.METHOD, ElementType.TYPE})
public @interface RequireRole {
    GlobalRole[] value();
}
