package com.campusconnect.feature.auth;

import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthenticatedOnly;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.auth.AuthDtos.OtpResult;
import com.campusconnect.feature.auth.AuthDtos.RegisterResult;
import com.campusconnect.feature.auth.AuthDtos.ResetSession;
import com.campusconnect.feature.auth.AuthDtos.Session;
import com.campusconnect.feature.auth.AuthDtos.SessionView;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/auth (routes/AuthRoutes.js). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService auth;
    private final RefreshCookies cookies;

    public AuthController(AuthService auth, RefreshCookies cookies) {
        this.auth = auth;
        this.cookies = cookies;
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<RegisterResult>> register(@Valid @RequestBody AuthDtos.RegisterBody body) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok("Account created. Enter the verification code we sent to your university email.", auth.register(body)));
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/verify-email")
    public ApiResponse<User> verifyEmail(@Valid @RequestBody AuthDtos.OtpBody body) {
        return ApiResponse.ok("Email verified successfully. You can now sign in.", auth.verifyEmail(body.email(), body.code()));
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/resend-verification")
    public ApiResponse<OtpResult> resendVerification(@Valid @RequestBody AuthDtos.EmailBody body) {
        return ApiResponse.ok("If an unverified account exists for this email, a new verification code has been sent.", new OtpResult(auth.resendVerification(body.email())));
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/login")
    public ResponseEntity<ApiResponse<SessionView>> login(@Valid @RequestBody AuthDtos.LoginBody body, @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Session session = auth.login(body.email(), body.password(), userAgent);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
                .body(ApiResponse.ok("Login successful", new SessionView(session.user(), session.accessToken())));
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<SessionView>> refresh(@CookieValue(value = RefreshCookies.NAME, required = false) String cookie,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String userAgent) {
        Session session = auth.refresh(tokenFrom(cookie, body), userAgent);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookies.set(session.refreshToken()))
                .body(ApiResponse.ok("Token refreshed", new SessionView(session.user(), session.accessToken())));
    }

    @PublicApi
    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(@CookieValue(value = RefreshCookies.NAME, required = false) String cookie, @RequestBody(required = false) Map<String, Object> body) {
        auth.logout(tokenFrom(cookie, body));
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clear()).body(ApiResponse.ok("Logged out successfully"));
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/forgot-password")
    public ApiResponse<OtpResult> forgotPassword(@Valid @RequestBody AuthDtos.EmailBody body) {
        return ApiResponse.ok("If an account exists for this email, a password reset code has been sent.", new OtpResult(auth.forgotPassword(body.email())));
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/verify-reset-code")
    public ApiResponse<ResetSession> verifyResetCode(@Valid @RequestBody AuthDtos.OtpBody body) {
        return ApiResponse.ok("Code confirmed. Choose a new password.", auth.verifyResetCode(body.email(), body.code()));
    }

    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponse<Void>> resetPassword(@Valid @RequestBody AuthDtos.ResetPasswordBody body) {
        auth.resetPassword(body.token(), body.password());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clear()).body(ApiResponse.ok("Password updated. Please sign in with your new password."));
    }

    @AuthenticatedOnly
    @PostMapping("/change-password")
    public ResponseEntity<ApiResponse<Void>> changePassword(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody AuthDtos.ChangePasswordBody body) {
        auth.changePassword(actor.id(), body.currentPassword(), body.newPassword());
        return ResponseEntity.ok().header(HttpHeaders.SET_COOKIE, cookies.clear()).body(ApiResponse.ok("Password changed. Please sign in again."));
    }

    @AuthenticatedOnly
    @GetMapping("/me")
    public ApiResponse<AuthUser> me(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Current user fetched", auth.getMe(actor.id()));
    }

    private static String tokenFrom(String cookie, Map<String, Object> body) {
        if (cookie != null && !cookie.isBlank()) {
            return cookie;
        }
        Object value = body == null ? null : body.get("refreshToken");
        return value instanceof String token ? token : null;
    }
}
