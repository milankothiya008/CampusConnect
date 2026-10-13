package com.campusconnect.feature.auth;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.validation.TrimmedSize;
import com.campusconnect.domain.user.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request bodies (validators/RequestValidators.js) and results of the auth routes. */
public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterBody(
            @NotNull(message = "Name must be 2-80 characters") @TrimmedSize(min = 2, max = 80, message = "Name must be 2-80 characters") String name,
            @NotBlank(message = "Valid email is required") @Email(message = "Valid email is required") String email,
            @NotNull(message = "Choose whether you are registering as a student or faculty") @Pattern(regexp = "STUDENT|FACULTY", message = "Choose whether you are registering as a student or faculty") String accountType,
            @NotNull(message = "Enter a valid 10-digit Indian mobile number") @Size(min = 10, max = 20, message = "Enter a valid 10-digit Indian mobile number") String phone,
            @NotNull(message = "Password must be 8-128 characters") @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
            @Pattern(regexp = "(?s).*[A-Za-z].*", message = "Password must include a letter")
            @Pattern(regexp = "(?s).*\\d.*", message = "Password must include a number") String password) {
    }

    public record LoginBody(
            @NotBlank(message = "Valid email is required") @Email(message = "Valid email is required") String email,
            @NotBlank(message = "Password is required") String password) {
    }

    public record EmailBody(@NotBlank(message = "Valid email is required") @Email(message = "Valid email is required") String email) {
    }

    public record OtpBody(
            @NotBlank(message = "Valid email is required") @Email(message = "Valid email is required") String email,
            @NotNull(message = "Enter the 6-digit code from your email") @Pattern(regexp = "^\\s*\\d{6}\\s*$", message = "Enter the 6-digit code from your email") String code) {
    }

    public record ResetPasswordBody(
            @NotNull(message = "Your reset session is invalid. Start the password reset again.") @Size(min = 20, max = 200, message = "Your reset session is invalid. Start the password reset again.") String token,
            @NotNull(message = "Password must be 8-128 characters") @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
            @Pattern(regexp = "(?s).*[A-Za-z].*", message = "Password must include a letter")
            @Pattern(regexp = "(?s).*\\d.*", message = "Password must include a number") String password) {
    }

    public record ChangePasswordBody(
            @NotBlank(message = "Current password is required") String currentPassword,
            @NotNull(message = "Password must be 8-128 characters") @Size(min = 8, max = 128, message = "Password must be 8-128 characters")
            @Pattern(regexp = "(?s).*[A-Za-z].*", message = "Password must include a letter")
            @Pattern(regexp = "(?s).*\\d.*", message = "Password must include a number") String newPassword) {
    }

    /** Static, so returning it reveals nothing about whether an account exists. */
    public record OtpPolicy(int length, int expiresInMinutes, int resendAfterSeconds) {
    }

    public record RegisterResult(User user, OtpPolicy otp) {
    }

    public record Session(User user, String accessToken, String refreshToken) {
    }

    public record SessionView(User user, String accessToken) {
    }

    public record ResetSession(String resetToken, int expiresInMinutes) {
    }

    public record OtpResult(OtpPolicy otp) {
    }

    public record Me(AuthUser user) {
    }
}
