package com.campusconnect.core.security;

import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.io.Serializable;
import java.time.Instant;

/**
 * The signed-in user as every request sees it (the Node {@code req.user}: USER_ACCOUNT_FIELDS). Immutable and
 * cached briefly per replica; changes to a user evict it everywhere.
 */
public record AuthUser(
        @JsonProperty("_id") String id,
        String name,
        String email,
        AccountType accountType,
        GlobalRole globalRole,
        @JsonProperty("isEmailVerified") boolean emailVerified,
        String departmentCode,
        String batchCode,
        String avatar,
        @JsonProperty("isActive") boolean active,
        Instant createdAt,
        String phone)
        implements Serializable {

    public static AuthUser of(User user) {
        return new AuthUser(
                user.getId(),
                user.getName(),
                user.getEmail(),
                user.getAccountType(),
                user.getGlobalRole(),
                user.isEmailVerified(),
                user.getDepartmentCode(),
                user.getBatchCode(),
                user.getAvatar(),
                user.isActive(),
                user.getCreatedAt(),
                user.getPhone());
    }

    public boolean isAdmin() {
        return globalRole == GlobalRole.ADMIN;
    }

    public boolean isFaculty() {
        return globalRole == GlobalRole.FACULTY;
    }

    public boolean isStudent() {
        return globalRole == GlobalRole.STUDENT && accountType == AccountType.STUDENT;
    }
}
