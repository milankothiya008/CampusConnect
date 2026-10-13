package com.campusconnect.core.security;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.domain.user.GlobalRole;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Arrays;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Applies each route's access level and role restriction (see the annotations in this package). Routes without
 * an annotation require a signed-in user with a verified e-mail — the Node {@code protect, requireVerified}.
 */
public class AccessPolicyInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        AccessLevel level = levelOf(method);
        if (level == AccessLevel.PUBLIC) {
            SecurityContextHolder.clearContext();
            return true;
        }
        var problem = (JwtAuthenticationFilter.TokenProblem) request.getAttribute(JwtAuthenticationFilter.TOKEN_PROBLEM);
        if (problem != null) {
            throw AppException.of(401, problem.message(), problem.code());
        }
        AuthUser user = currentUser();
        if (level == AccessLevel.OPTIONAL) {
            return true;
        }
        if (user == null) {
            throw AppException.unauthorized("Authentication required");
        }
        if (level == AccessLevel.VERIFIED && !user.emailVerified()) {
            throw AppException.forbidden("Email verification required", ErrorCode.UNVERIFIED_EMAIL);
        }
        RequireRole roles = find(method, RequireRole.class);
        if (roles != null && Arrays.stream(roles.value()).noneMatch(role -> role == user.globalRole())) {
            throw AppException.forbidden("You do not have permission to perform this action");
        }
        return true;
    }

    static AccessLevel levelOf(HandlerMethod method) {
        if (find(method, PublicApi.class) != null) {
            return AccessLevel.PUBLIC;
        }
        if (find(method, OptionalAuth.class) != null) {
            return AccessLevel.OPTIONAL;
        }
        if (find(method, AuthenticatedOnly.class) != null) {
            return AccessLevel.AUTHENTICATED;
        }
        return AccessLevel.VERIFIED;
    }

    /** Method annotation first, then the controller class. */
    static <A extends java.lang.annotation.Annotation> A find(HandlerMethod method, Class<A> type) {
        A onMethod = AnnotatedElementUtils.findMergedAnnotation(method.getMethod(), type);
        return onMethod != null ? onMethod : AnnotatedElementUtils.findMergedAnnotation(method.getBeanType(), type);
    }

    public static AuthUser currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof AuthUser user ? user : null;
    }

    public static boolean hasRole(AuthUser user, GlobalRole role) {
        return user != null && user.globalRole() == role;
    }
}
