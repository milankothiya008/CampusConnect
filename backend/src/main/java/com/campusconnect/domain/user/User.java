package com.campusconnect.domain.user;

import com.campusconnect.domain.common.BaseDocument;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A university account (models/User.js). Students and faculty sign up with their university e-mail; the
 * account type, department and batch are derived from the address. Secrets (password hash, OTP hashes, reset
 * tokens, calendar key version) are never serialised.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("users")
public class User extends BaseDocument {

    private String name;

    @Indexed(name = "email_1", unique = true)
    private String email;

    @JsonIgnore
    private String password;

    private AccountType accountType;

    @Indexed(name = "globalRole_1")
    private GlobalRole globalRole = GlobalRole.STUDENT;

    @Indexed(name = "departmentCode_1")
    private String departmentCode;

    private String batchCode;

    /** Profile photo URL in media storage. */
    private String avatar;

    /** "+91" and ten digits (core.util.Phones). */
    private String phone;

    private boolean isEmailVerified = false;

    // One-time codes: only keyed hashes are stored; a code dies after too many wrong guesses.
    @JsonIgnore private String emailVerificationCodeHash;
    @JsonIgnore private Instant emailVerificationCodeExpires;
    @JsonIgnore private int emailVerificationAttempts = 0;
    @JsonIgnore private Instant emailVerificationSentAt;
    @JsonIgnore private String passwordResetCodeHash;
    @JsonIgnore private Instant passwordResetCodeExpires;
    @JsonIgnore private int passwordResetAttempts = 0;
    @JsonIgnore private Instant passwordResetSentAt;
    /** Short-lived token handed out once the reset code is confirmed. */
    @JsonIgnore private String passwordResetTokenHash;
    @JsonIgnore private Instant passwordResetExpires;

    private boolean isActive = true;

    private EmailPreferences emailPreferences = new EmailPreferences();

    // Chat: activity status, message notifications and blocked people.
    private Instant lastSeenAt;
    private boolean showActivityStatus = true;
    private boolean chatNotifications = true;

    @Field(targetType = FieldType.OBJECT_ID)
    private List<String> blockedUsers = new ArrayList<>();

    /** Version of the private calendar-feed link; resetting the link bumps it. */
    @JsonIgnore
    private int calendarKeyVersion = 0;

    // Boolean fields named "is…": Jackson would publish them as "emailVerified"/"active". Keep the original
    // JSON names (the frontend reads user.isEmailVerified / user.isActive); MongoDB uses the field names.
    @JsonProperty("isEmailVerified")
    public boolean isEmailVerified() {
        return isEmailVerified;
    }

    public void setEmailVerified(boolean value) {
        this.isEmailVerified = value;
    }

    @JsonProperty("isActive")
    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean value) {
        this.isActive = value;
    }
}
