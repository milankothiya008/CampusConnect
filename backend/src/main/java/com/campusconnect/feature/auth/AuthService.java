package com.campusconnect.feature.auth;

import com.campusconnect.core.audit.AuditService;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.mail.MailService;
import com.campusconnect.core.ratelimit.AttemptLimiter;
import com.campusconnect.core.mail.Recipient;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.core.security.JwtTokens;
import com.campusconnect.core.security.PasswordHasher;
import com.campusconnect.core.tokens.OtpCodes;
import com.campusconnect.core.util.Hmacs;
import com.campusconnect.core.util.Phones;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.RefreshToken;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.auth.AuthDtos.OtpPolicy;
import com.campusconnect.feature.auth.AuthDtos.RegisterResult;
import com.campusconnect.feature.auth.AuthDtos.ResetSession;
import com.campusconnect.feature.auth.AuthDtos.Session;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.stereotype.Service;

/** Accounts, sign-in and sessions (services/AuthService.js). */
@Slf4j
@Service
public class AuthService {

    static final int OTP_TTL_MINUTES = 10;
    static final int OTP_MAX_ATTEMPTS = 5;
    static final int OTP_RESEND_COOLDOWN_SECONDS = 60;
    /** Once the reset code is confirmed, the user has this long to choose a new password. */
    static final int RESET_SESSION_TTL_MINUTES = 15;
    /** A rotated refresh token presented again within this window is a concurrent refresh (two tabs), not theft. */
    static final Duration REFRESH_REUSE_GRACE = Duration.ofSeconds(10);

    public static final OtpPolicy OTP_POLICY = new OtpPolicy(OtpCodes.LENGTH, OTP_TTL_MINUTES, OTP_RESEND_COOLDOWN_SECONDS);

    /** The four fields of one kind of one-time code. */
    enum Purpose {
        VERIFICATION("emailVerificationCodeHash", "emailVerificationCodeExpires", "emailVerificationAttempts", "emailVerificationSentAt"),
        RESET("passwordResetCodeHash", "passwordResetCodeExpires", "passwordResetAttempts", "passwordResetSentAt");

        final String hash;
        final String expires;
        final String attempts;
        final String sentAt;

        Purpose(String hash, String expires, String attempts, String sentAt) {
            this.hash = hash;
            this.expires = expires;
            this.attempts = attempts;
            this.sentAt = sentAt;
        }
    }

    private final AttemptLimiter attempts;
    private final MongoOperations mongo;
    private final PasswordHasher passwords;
    private final JwtTokens tokens;
    private final OtpCodes otpCodes;
    private final UniversityRules rules;
    private final MailService mail;
    private final AuditService audit;
    private final AuthUserService authUsers;
    private final com.campusconnect.core.config.AppProperties properties;

    public AuthService(MongoOperations mongo, PasswordHasher passwords, JwtTokens tokens, OtpCodes otpCodes, UniversityRules rules, MailService mail,
            AuditService audit, AuthUserService authUsers, com.campusconnect.core.config.AppProperties properties, AttemptLimiter attempts) {
        this.attempts = attempts;
        this.mongo = mongo;
        this.passwords = passwords;
        this.tokens = tokens;
        this.otpCodes = otpCodes;
        this.rules = rules;
        this.mail = mail;
        this.audit = audit;
        this.authUsers = authUsers;
        this.properties = properties;
    }

    static String normalizeEmail(String email) {
        return String.valueOf(email == null ? "" : email).trim().toLowerCase(Locale.ROOT);
    }

    private User findByEmail(String email) {
        return mongo.findOne(Query.query(Criteria.where("email").is(normalizeEmail(email))), User.class);
    }

    private static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(new ObjectId(id)));
    }

    static void assertPasswordStrength(String password) {
        String value = password == null ? "" : password;
        if (value.length() < 8 || !value.matches("(?s).*[A-Za-z].*") || !value.matches("(?s).*\\d.*")) {
            throw AppException.badRequest("Password must be at least 8 characters and include a letter and a number", ErrorCode.VALIDATION_ERROR);
        }
    }

    // ---------------------------------------------------------------- one-time codes

    /** Stores a fresh code for this purpose and returns the plain code for the e-mail. */
    private String issueOtp(String userId, Purpose purpose) {
        String code = OtpCodes.generate();
        Instant now = Instant.now();
        mongo.updateFirst(byId(userId), new Update()
                .set(purpose.hash, otpCodes.hash(userId, code))
                .set(purpose.expires, now.plus(Duration.ofMinutes(OTP_TTL_MINUTES)))
                .set(purpose.attempts, 0)
                .set(purpose.sentAt, now)
                .set("updatedAt", now), User.class);
        return code;
    }

    private void clearOtp(String userId, Purpose purpose, Update extra) {
        Update update = extra == null ? new Update() : extra;
        mongo.updateFirst(byId(userId), update.unset(purpose.hash).unset(purpose.expires).set(purpose.attempts, 0).set("updatedAt", Instant.now()), User.class);
    }

    private static boolean recentlySent(Instant sentAt) {
        return sentAt != null && Duration.between(sentAt, Instant.now()).toSeconds() < OTP_RESEND_COOLDOWN_SECONDS;
    }

    /**
     * Counts each guess atomically before checking it, so parallel requests cannot exceed the attempt limit. On
     * success the code is cleared and {@code onSuccess} changes are applied in the same write.
     */
    private User consumeOtp(String email, String code, Purpose purpose, Update onSuccess) {
        User user = mongo.findAndModify(
                Query.query(Criteria.where("email").is(normalizeEmail(email))
                        .and(purpose.hash).ne(null)
                        .and(purpose.expires).gt(Instant.now())
                        .and(purpose.attempts).lt(OTP_MAX_ATTEMPTS)),
                new Update().inc(purpose.attempts, 1),
                FindAndModifyOptions.options().returnNew(true),
                User.class);
        if (user == null) {
            throw AppException.badRequest("This code has expired or is no longer valid. Request a new code.", ErrorCode.OTP_EXPIRED);
        }
        String storedHash = purpose == Purpose.VERIFICATION ? user.getEmailVerificationCodeHash() : user.getPasswordResetCodeHash();
        int attempts = purpose == Purpose.VERIFICATION ? user.getEmailVerificationAttempts() : user.getPasswordResetAttempts();
        if (!otpCodes.matches(user.getId(), String.valueOf(code == null ? "" : code).trim(), storedHash)) {
            int attemptsLeft = OTP_MAX_ATTEMPTS - attempts;
            if (attemptsLeft <= 0) {
                clearOtp(user.getId(), purpose, null);
                throw AppException.badRequest("Too many incorrect attempts. Request a new code.", ErrorCode.OTP_EXPIRED);
            }
            throw AppException.badRequest("Incorrect code. " + attemptsLeft + " attempt" + (attemptsLeft == 1 ? "" : "s") + " left.", ErrorCode.OTP_INVALID)
                    .withDetails(Map.of("attemptsLeft", attemptsLeft));
        }
        clearOtp(user.getId(), purpose, onSuccess);
        authUsers.evict(user.getId());
        return mongo.findById(user.getId(), User.class);
    }

    // ---------------------------------------------------------------- sessions

    private Session issueTokens(User user, String userAgent) {
        String accessToken = tokens.issueAccessToken(user.getId(), user.getGlobalRole().name());
        String refreshToken = tokens.issueRefreshToken(user.getId());
        RefreshToken stored = new RefreshToken();
        stored.setUser(user.getId());
        stored.setTokenHash(Hmacs.sha256Hex(refreshToken));
        stored.setExpiresAt(Instant.now().plus(properties.getJwt().getRefreshTokenTtl()));
        stored.setUserAgent(userAgent == null ? null : userAgent.length() > 300 ? userAgent.substring(0, 300) : userAgent);
        mongo.insert(stored);
        return new Session(user, accessToken, refreshToken);
    }

    // ---------------------------------------------------------------- the routes

    /** The selected account type decides which e-mail rule applies, so the message fits the format they meant. */
    private void assertEmailMatchesRole(String email, AccountType accountType) {
        if (!rules.isUniversityEmail(email)) {
            throw AppException.badRequest("Use your @" + properties.getUniversity().getDomain() + " university email", ErrorCode.INVALID_EMAIL);
        }
        if (accountType == AccountType.STUDENT && rules.parseStudentEmail(email) == null) {
            String hint = rules.parseFacultyEmail(email) != null ? " This looks like a faculty email — choose Faculty instead." : "";
            throw AppException.badRequest(rules.studentFormatHint() + "." + hint, ErrorCode.INVALID_EMAIL);
        }
        if (accountType == AccountType.FACULTY && rules.parseFacultyEmail(email) == null) {
            String hint = rules.parseStudentEmail(email) != null ? " This looks like a student email — choose Student instead." : "";
            throw AppException.badRequest(rules.facultyFormatHint() + "." + hint, ErrorCode.INVALID_EMAIL);
        }
    }

    public RegisterResult register(AuthDtos.RegisterBody body) {
        if (isBlank(body.name()) || isBlank(body.email()) || isBlank(body.password())) {
            throw AppException.badRequest("Name, email and password are required", ErrorCode.VALIDATION_ERROR);
        }
        // Everyone gives a mobile number when signing up (club contacts, team coordination).
        String mobile = Phones.normalize(body.phone());
        if (mobile == null) {
            throw AppException.badRequest("Enter a valid 10-digit Indian mobile number", ErrorCode.VALIDATION_ERROR);
        }
        AccountType accountType;
        try {
            accountType = AccountType.valueOf(String.valueOf(body.accountType()));
        } catch (IllegalArgumentException e) {
            throw AppException.badRequest("Choose whether you are registering as a student or faculty", ErrorCode.VALIDATION_ERROR);
        }
        assertPasswordStrength(body.password());

        String email = normalizeEmail(body.email());
        assertEmailMatchesRole(email, accountType);
        UniversityRules.Classified classified = rules.classify(email);
        User existing = findByEmail(email);
        if (existing != null && !existing.isEmailVerified()) {
            throw AppException.conflict("An account with this email is waiting for verification. Enter the code we emailed you, or request a new one.", ErrorCode.UNVERIFIED_EMAIL);
        }
        if (existing != null) {
            throw AppException.conflict("An account with this email already exists", ErrorCode.CONFLICT);
        }

        User user = new User();
        user.setName(body.name().strip());
        user.setEmail(email);
        user.setPassword(passwords.hash(body.password()));
        user.setAccountType(classified.accountType());
        user.setGlobalRole(classified.accountType() == AccountType.FACULTY ? GlobalRole.FACULTY : GlobalRole.STUDENT);
        user.setDepartmentCode(classified.departmentCode());
        user.setBatchCode(classified.batchCode());
        user.setPhone(mobile);
        user.setEmailVerified(false);
        try {
            user = mongo.insert(user);
        } catch (DuplicateKeyException e) {
            // Two sign-ups for the same address at the same moment.
            throw AppException.conflict("An account with this email already exists", ErrorCode.CONFLICT);
        }
        String code = issueOtp(user.getId(), Purpose.VERIFICATION);
        mail.sendVerificationCode(new Recipient(user.getId(), user.getName(), user.getEmail()), code, OTP_TTL_MINUTES);
        audit.record(AuditAction.USER_REGISTERED, user.getId(), "User", user.getId(), Map.of("accountType", user.getAccountType().name()));
        return new RegisterResult(mongo.findById(user.getId(), User.class), OTP_POLICY);
    }

    public User verifyEmail(String email, String code) {
        return consumeOtp(email, code, Purpose.VERIFICATION, new Update().set("isEmailVerified", true));
    }

    /** Responds identically whether or not the account exists, to avoid account enumeration. */
    public OtpPolicy resendVerification(String email) {
        User user = findByEmail(email);
        if (user == null || user.isEmailVerified() || !user.isActive() || recentlySent(user.getEmailVerificationSentAt())) {
            return OTP_POLICY;
        }
        String code = issueOtp(user.getId(), Purpose.VERIFICATION);
        mail.sendVerificationCode(new Recipient(user.getId(), user.getName(), user.getEmail()), code, OTP_TTL_MINUTES);
        return OTP_POLICY;
    }

    public Session login(String email, String password, String userAgent) {
        if (isBlank(email) || isBlank(password)) {
            throw AppException.badRequest("Email and password are required", ErrorCode.VALIDATION_ERROR);
        }
        String normalized = normalizeEmail(email);
        // A format check reveals nothing about which accounts exist, so it can fail fast with a helpful message.
        if (!rules.isValidUniversityEmailFormat(normalized)) {
            throw AppException.badRequest("Sign in with your university email: " + rules.emailFormatHint(), ErrorCode.INVALID_EMAIL);
        }
        // Wrong passwords are also counted per account (on top of the per-address limit), so guessing can't be spread
        // over many addresses. Unknown addresses count the same way, which keeps them indistinguishable.
        String account = "signin:" + normalized;
        int failures = properties.getRateLimit().getSignInFailures();
        if (attempts.exhausted(account, failures)) {
            throw AppException.of(429, "Too many wrong passwords for this account. Try again in a few minutes or reset your password.", ErrorCode.RATE_LIMITED);
        }
        User user = findByEmail(normalized);
        if (user == null || !passwords.matches(password, user.getPassword())) {
            attempts.spend(account, failures);
            log.warn("Authentication failure for {}", normalized);
            throw AppException.of(401, "Invalid email or password", ErrorCode.INVALID_CREDENTIALS);
        }
        if (!user.isActive()) {
            throw AppException.forbidden("Your account has been disabled. Contact the university admin.", ErrorCode.ACCOUNT_DISABLED);
        }
        if (!user.isEmailVerified()) {
            throw AppException.forbidden("Please verify your email before logging in", ErrorCode.UNVERIFIED_EMAIL);
        }
        return issueTokens(user, userAgent);
    }

    public Session refresh(String incoming, String userAgent) {
        if (isBlank(incoming)) {
            throw AppException.of(401, "Refresh token is required", ErrorCode.UNAUTHORIZED);
        }
        Jwt decoded;
        try {
            decoded = tokens.verifyRefresh(incoming);
        } catch (JwtException | IllegalArgumentException e) {
            throw AppException.of(401, "Invalid or expired refresh token", ErrorCode.TOKEN_INVALID);
        }
        String userId = decoded.getSubject();
        if (userId == null || !ObjectId.isValid(userId)) {
            throw AppException.of(401, "Invalid or expired refresh token", ErrorCode.TOKEN_INVALID);
        }
        String tokenHash = Hmacs.sha256Hex(incoming);
        RefreshToken stored = mongo.findOne(Query.query(Criteria.where("tokenHash").is(tokenHash).and("user").is(new ObjectId(userId))), RefreshToken.class);
        if (stored == null) {
            throw AppException.of(401, "Refresh token has been revoked", ErrorCode.REFRESH_REVOKED);
        }
        if (stored.getRevokedAt() != null) {
            boolean rotatedRecently = stored.getReplacedByTokenHash() != null
                    && Duration.between(stored.getRevokedAt(), Instant.now()).compareTo(REFRESH_REUSE_GRACE) < 0;
            if (!rotatedRecently) {
                // Reuse of an old token: someone may have stolen it — end every session of this user.
                logoutAll(userId);
                log.warn("Refresh token reuse detected; family revoked for user {}", userId);
            }
            throw AppException.of(401, "Refresh token has been revoked", ErrorCode.REFRESH_REVOKED);
        }
        if (stored.getExpiresAt().isBefore(Instant.now())) {
            throw AppException.of(401, "Refresh token has expired", ErrorCode.TOKEN_EXPIRED);
        }
        User user = mongo.findById(userId, User.class);
        if (user == null || !user.isActive()) {
            throw AppException.of(401, "User not found or inactive", ErrorCode.UNAUTHORIZED);
        }
        // Claim the token atomically so two concurrent refreshes cannot both rotate it.
        RefreshToken claimed = mongo.findAndModify(
                Query.query(Criteria.where("_id").is(new ObjectId(stored.getId())).and("revokedAt").is(null)),
                new Update().set("revokedAt", Instant.now()),
                RefreshToken.class);
        if (claimed == null) {
            throw AppException.of(401, "Refresh token has been revoked", ErrorCode.REFRESH_REVOKED);
        }
        Session session = issueTokens(user, userAgent);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(stored.getId()))),
                new Update().set("replacedByTokenHash", Hmacs.sha256Hex(session.refreshToken())), RefreshToken.class);
        return session;
    }

    public void logout(String incoming) {
        if (isBlank(incoming)) {
            return;
        }
        mongo.updateFirst(Query.query(Criteria.where("tokenHash").is(Hmacs.sha256Hex(incoming)).and("revokedAt").is(null)),
                new Update().set("revokedAt", Instant.now()), RefreshToken.class);
    }

    public void logoutAll(String userId) {
        mongo.updateMulti(Query.query(Criteria.where("user").is(new ObjectId(userId)).and("revokedAt").is(null)),
                new Update().set("revokedAt", Instant.now()), RefreshToken.class);
    }

    /** Responds identically whether or not the account exists, to avoid account enumeration. */
    public OtpPolicy forgotPassword(String email) {
        User user = findByEmail(email);
        if (user == null || !user.isActive() || recentlySent(user.getPasswordResetSentAt())) {
            return OTP_POLICY;
        }
        String code = issueOtp(user.getId(), Purpose.RESET);
        mail.sendPasswordResetCode(new Recipient(user.getId(), user.getName(), user.getEmail()), code, OTP_TTL_MINUTES);
        return OTP_POLICY;
    }

    /**
     * Exchanges a correct reset code for a short-lived token that authorises setting the new password. The token
     * goes only to the client that proved the code, never into an e-mail or URL.
     */
    public ResetSession verifyResetCode(String email, String code) {
        String resetToken = Hmacs.randomHex(32);
        User user = consumeOtp(email, code, Purpose.RESET, null);
        if (!user.isActive()) {
            throw AppException.forbidden("Your account has been disabled. Contact the university admin.", ErrorCode.ACCOUNT_DISABLED);
        }
        mongo.updateFirst(byId(user.getId()), new Update()
                .set("passwordResetTokenHash", Hmacs.sha256Hex(resetToken))
                .set("passwordResetExpires", Instant.now().plus(Duration.ofMinutes(RESET_SESSION_TTL_MINUTES)))
                .set("updatedAt", Instant.now()), User.class);
        return new ResetSession(resetToken, RESET_SESSION_TTL_MINUTES);
    }

    public void resetPassword(String token, String password) {
        if (isBlank(token)) {
            throw AppException.badRequest("Your reset session is missing. Start the password reset again.", ErrorCode.VALIDATION_ERROR);
        }
        assertPasswordStrength(password);
        String hash = passwords.hash(password);
        // One atomic write: the reset token can be used once only.
        User user = mongo.findAndModify(
                Query.query(Criteria.where("passwordResetTokenHash").is(Hmacs.sha256Hex(token)).and("passwordResetExpires").gt(Instant.now())),
                new Update()
                        .set("password", hash)
                        .unset("passwordResetTokenHash")
                        .unset("passwordResetExpires")
                        // Entering the e-mailed reset code proves ownership of the address.
                        .set("isEmailVerified", true)
                        .set("updatedAt", Instant.now()),
                User.class);
        if (user == null) {
            throw AppException.badRequest("Your reset session has expired. Start the password reset again.", ErrorCode.TOKEN_INVALID);
        }
        authUsers.evict(user.getId());
        logoutAll(user.getId());
        audit.record(AuditAction.PASSWORD_RESET, user.getId(), "User", user.getId());
    }

    public void changePassword(String userId, String currentPassword, String newPassword) {
        User user = mongo.findById(userId, User.class);
        if (user == null || !passwords.matches(currentPassword == null ? "" : currentPassword, user.getPassword())) {
            throw AppException.badRequest("Current password is incorrect", ErrorCode.INVALID_CREDENTIALS);
        }
        assertPasswordStrength(newPassword);
        mongo.updateFirst(byId(userId), new Update().set("password", passwords.hash(newPassword)).set("updatedAt", Instant.now()), User.class);
        logoutAll(userId);
    }

    /** The account fields a signed-in user sees of themselves (USER_ACCOUNT_FIELDS), always fresh. */
    public AuthUser getMe(String userId) {
        User user = mongo.findById(userId, User.class);
        if (user == null) {
            throw AppException.notFound("User not found");
        }
        return AuthUser.of(user);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
