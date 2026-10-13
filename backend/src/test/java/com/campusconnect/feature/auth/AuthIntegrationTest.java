package com.campusconnect.feature.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** Port of tests/integration/auth.test.js. */
class AuthIntegrationTest extends IntegrationTest {

    @BeforeEach
    void seed() {
        data.seedReferenceData();
    }

    /** Registrations default to the student role unless a test chooses otherwise. */
    Response register(Object... pairs) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("accountType", "STUDENT");
        body.put("phone", "98765 43210");
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return api.post("/api/auth/register", body);
    }

    String code(String email) {
        DevInbox.Entry mail = data.lastMailTo(email);
        return mail == null ? null : mail.code();
    }

    void setUser(String email, Update update) {
        mongo.updateFirst(Query.query(Criteria.where("email").is(email)), update, "users");
    }

    Document userDocument(String email) {
        return mongo.findOne(Query.query(Criteria.where("email").is(email)), Document.class, "users");
    }

    @Nested
    class Registration {

        @Test
        void derivesAStudentAccountFromTheUniversityEmailAndEmailsACodeItNeverReturns() {
            Response res = register("name", "Asha Patel", "email", "24CEUOG001@ddu.ac.in", "password", "Secret123");

            assertThat(res.status()).isEqualTo(201);
            assertThat(res.string("$.data.user.accountType")).isEqualTo("STUDENT");
            assertThat(res.string("$.data.user.globalRole")).isEqualTo("STUDENT");
            assertThat(res.string("$.data.user.departmentCode")).isEqualTo("CE");
            assertThat(res.string("$.data.user.batchCode")).isEqualTo("24");
            assertThat((Boolean) res.at("$.data.user.isEmailVerified")).isFalse();
            assertThat((Map<String, Object>) res.at("$.data.otp")).isEqualTo(Map.of("length", 6, "expiresInMinutes", 10, "resendAfterSeconds", 60));

            DevInbox.Entry mail = data.lastMailTo("24ceuog001@ddu.ac.in");
            assertThat(mail.code()).matches("^\\d{6}$");
            assertThat(mail.text()).contains(mail.code());
            assertThat(mail.text()).doesNotContainPattern("https?://");
            assertThat(res.body()).doesNotContainPattern("CodeHash|Attempts|password");
            assertThat(res.body()).doesNotContain(mail.code());
        }

        @Test
        void registersFacultyWithTheFirstNameDepartmentFormat() {
            Response res = register("name", "Mrudang Shah", "email", "mrudang.ce@ddu.ac.in", "password", "Secret123", "accountType", "FACULTY", "globalRole", "ADMIN");

            assertThat(res.status()).isEqualTo(201);
            assertThat(res.string("$.data.user.accountType")).isEqualTo("FACULTY");
            assertThat(res.string("$.data.user.globalRole")).isEqualTo("FACULTY");
            assertThat(res.string("$.data.user.departmentCode")).isEqualTo("CE");
            assertThat((Object) res.at("$.data.user.batchCode")).isNull();
            assertThat(data.lastMailTo("mrudang.ce@ddu.ac.in")).isNotNull();
        }

        @Test
        void requiresARoleChoice() {
            Response res = api.post("/api/auth/register", Map.of("name", "X Y", "email", "24ceuog001@ddu.ac.in", "password", "Secret123"));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.message()).contains("student or faculty");
        }

        @Test
        void checksTheEmailAgainstTheChosenRolesFormat() {
            Response asFaculty = register("name", "X Y", "email", "24ceuog001@ddu.ac.in", "password", "Secret123", "accountType", "FACULTY");
            assertThat(asFaculty.errorCode()).isEqualTo("INVALID_EMAIL");
            assertThat(asFaculty.message()).containsPattern("mrudang.ce@ddu.ac.in.*looks like a student email");

            Response asStudent = register("name", "X Y", "email", "mrudang.ce@ddu.ac.in", "password", "Secret123", "accountType", "STUDENT");
            assertThat(asStudent.errorCode()).isEqualTo("INVALID_EMAIL");
            assertThat(asStudent.message()).containsPattern("24ceuog001@ddu.ac.in.*looks like a faculty email");

            Response badFaculty = register("name", "X Y", "email", "mrudang.shah.ce@ddu.ac.in", "password", "Secret123", "accountType", "FACULTY");
            assertThat(badFaculty.message()).contains("Faculty emails must be");
        }

        @Test
        void rejectsNonUniversityEmailsUnknownBatchesAndWeakPasswords() {
            assertThat(register("name", "X Y", "email", "someone@gmail.com", "password", "Secret123").errorCode()).isEqualTo("INVALID_EMAIL");
            assertThat(register("name", "X Y", "email", "19CEUOG001@ddu.ac.in", "password", "Secret123").errorCode()).isEqualTo("INVALID_EMAIL");
            assertThat(register("name", "X Y", "email", "24CEUOG001@ddu.ac.in", "password", "password").status()).isEqualTo(400);
        }

        @Test
        void rejectsEmailsThatDoNotFollowTheUniversityFormats() {
            for (String email : List.of("24CE1234@ddu.ac.in", "24ceuog01@ddu.ac.in", "brij.patel.ce@ddu.ac.in", "brij1.ce@ddu.ac.in")) {
                Response res = register("name", "X Y", "email", email, "password", "Secret123");
                assertThat(res.errorCode()).as(email).isEqualTo("INVALID_EMAIL");
                assertThat(res.message()).as(email).containsPattern("24ceuog001@ddu\\.ac\\.in");
            }
        }

        @Test
        void rejectsUnknownDepartmentsInEitherFormat() {
            assertThat(register("name", "X Y", "email", "24zzuog001@ddu.ac.in", "password", "Secret123").message()).contains("Department 'ZZ'");
            assertThat(register("name", "X Y", "email", "brij.zz@ddu.ac.in", "password", "Secret123", "accountType", "FACULTY").message()).contains("Department 'ZZ'");
        }

        @Test
        void rejectsDuplicateAccountsPointingUnverifiedOnesToTheCodeScreen() {
            register("name", "Asha Patel", "email", "24CEUOG001@ddu.ac.in", "password", "Secret123");
            Response pending = register("name", "Asha Patel", "email", "24ceuog001@ddu.ac.in", "password", "Secret123");
            assertThat(pending.status()).isEqualTo(409);
            assertThat(pending.errorCode()).isEqualTo("UNVERIFIED_EMAIL");

            setUser("24ceuog001@ddu.ac.in", new Update().set("isEmailVerified", true));
            Response taken = register("name", "Asha Patel", "email", "24ceuog001@ddu.ac.in", "password", "Secret123");
            assertThat(taken.status()).isEqualTo(409);
            assertThat(taken.errorCode()).isEqualTo("CONFLICT");
        }
    }

    @Nested
    class VerificationLoginRefreshAndLogout {

        static final String EMAIL = "24ceuog201@ddu.ac.in";

        Response verify(String code) {
            return verify(code, EMAIL);
        }

        Response verify(String code, String address) {
            return api.post("/api/auth/verify-email", Map.of("email", address, "code", code));
        }

        Response registerAndVerify() {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            return verify(code(EMAIL));
        }

        Response login(String email, String password) {
            return api.post("/api/auth/login", Map.of("email", email, "password", password));
        }

        String wrongCode(String code) {
            return String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);
        }

        @Test
        void blocksLoginUntilTheEmailIsVerified() {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            Response res = login(EMAIL, "Secret123");
            assertThat(res.status()).isEqualTo(403);
            assertThat(res.errorCode()).isEqualTo("UNVERIFIED_EMAIL");
        }

        @Test
        void verifiesTheEmailLogsInAndFetchesTheProfile() {
            Response verified = registerAndVerify();
            assertThat(verified.status()).isEqualTo(200);
            assertThat((Boolean) verified.at("$.data.isEmailVerified")).isTrue();

            Response login = login(EMAIL, "Secret123");
            assertThat(login.status()).isEqualTo(200);
            assertThat(login.string("$.data.accessToken")).isNotBlank();
            assertThat(String.join(";", login.header("Set-Cookie"))).containsPattern("(?i)refreshToken=.*HttpOnly");

            Response me = api.withToken(login.string("$.data.accessToken")).get("/api/auth/me");
            assertThat(me.string("$.data.email")).isEqualTo(EMAIL);
        }

        @Test
        void aCodeIsSingleUseAndBelongsToItsOwnEmail() {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            register("name", "Other Student", "email", "24ceuog202@ddu.ac.in", "password", "Secret123");
            String code = code(EMAIL);

            assertThat(verify(code, "24ceuog202@ddu.ac.in").status()).isEqualTo(400);
            assertThat(verify(code).status()).isEqualTo(200);
            Response reused = verify(code);
            assertThat(reused.status()).isEqualTo(400);
            assertThat(reused.errorCode()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        void rejectsMalformedCodesBeforeCheckingThem() {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            for (String code : List.of("12345", "1234567", "abcdef", "")) {
                Response res = verify(code);
                assertThat(res.status()).as(code).isEqualTo(400);
                assertThat(res.errorCode()).as(code).isEqualTo("VALIDATION_ERROR");
            }
            assertThat(userDocument(EMAIL).getInteger("emailVerificationAttempts")).isZero();
        }

        @Test
        void countsWrongGuessesAndKillsTheCodeAfterFive() {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            String code = code(EMAIL);

            Response first = verify(wrongCode(code));
            assertThat(first.errorCode()).isEqualTo("OTP_INVALID");
            assertThat(first.message()).isEqualTo("Incorrect code. 4 attempts left.");
            assertThat((Map<String, Object>) first.at("$.details")).isEqualTo(Map.of("attemptsLeft", 4));

            for (int i = 0; i < 3; i++) {
                verify(wrongCode(code));
            }
            Response fifth = verify(wrongCode(code));
            assertThat(fifth.errorCode()).isEqualTo("OTP_EXPIRED");
            assertThat(fifth.message()).contains("Too many incorrect attempts");

            // Even the right code no longer works once the limit is reached.
            assertThat(verify(code).errorCode()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        void parallelGuessesCannotExceedTheAttemptLimit() throws Exception {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            String code = code(EMAIL);

            List<Callable<Response>> guesses = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                guesses.add(() -> verify(wrongCode(code)));
            }
            List<Response> results = new ArrayList<>();
            try (ExecutorService pool = Executors.newFixedThreadPool(12)) {
                for (Future<Response> future : pool.invokeAll(guesses)) {
                    results.add(future.get());
                }
            }
            assertThat(results.stream().filter(res -> "OTP_INVALID".equals(res.errorCode()))).hasSize(4);
            assertThat(verify(code).errorCode()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        void rejectsAnExpiredCode() {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            String code = code(EMAIL);
            setUser(EMAIL, new Update().set("emailVerificationCodeExpires", Instant.now().minusSeconds(1)));

            Response res = verify(code);
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.errorCode()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        void resendingWaitsForTheCooldownThenReplacesTheOldCode() {
            register("name", "Ravi Shah", "email", EMAIL, "password", "Secret123");
            String firstCode = code(EMAIL);
            int sentBefore = data.mailCount();

            Response tooSoon = api.post("/api/auth/resend-verification", Map.of("email", EMAIL));
            assertThat(tooSoon.status()).isEqualTo(200);
            assertThat((Integer) tooSoon.at("$.data.otp.resendAfterSeconds")).isEqualTo(60);
            assertThat(data.mailCount()).isEqualTo(sentBefore);

            setUser(EMAIL, new Update().set("emailVerificationSentAt", Instant.now().minusSeconds(61)));
            api.post("/api/auth/resend-verification", Map.of("email", EMAIL));
            assertThat(data.mailCount()).isEqualTo(sentBefore + 1);
            String secondCode = code(EMAIL);

            if (!secondCode.equals(firstCode)) {
                assertThat(verify(firstCode).status()).isEqualTo(400);
            }
            assertThat(verify(secondCode).status()).isEqualTo(200);
        }

        @Test
        void resendAnswersTheSameForUnknownAndVerifiedAccountsWithoutSendingMail() {
            registerAndVerify();
            int sentBefore = data.mailCount();

            Response verified = api.post("/api/auth/resend-verification", Map.of("email", EMAIL));
            Response unknown = api.post("/api/auth/resend-verification", Map.of("email", "24ceuog999@ddu.ac.in"));
            assertThat(verified.body()).isEqualTo(unknown.body());
            assertThat(data.mailCount()).isEqualTo(sentBefore);
        }

        @Test
        void loginOnlyAcceptsEmailsInTheUniversityFormats() {
            for (String bad : List.of("someone@gmail.com", "24ce1234@ddu.ac.in", "brij.patel.ce@ddu.ac.in")) {
                Response res = login(bad, "Secret123");
                assertThat(res.status()).as(bad).isEqualTo(400);
                assertThat(res.errorCode()).as(bad).isEqualTo("INVALID_EMAIL");
            }
        }

        @Test
        void theUniversityAdminSignsInAsAdminAtTheDomainWhichNobodyCanRegister() {
            com.campusconnect.domain.user.User admin = new com.campusconnect.domain.user.User();
            admin.setName("University Admin");
            admin.setEmail("admin@ddu.ac.in");
            admin.setPassword(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(4).encode("AdminPass123"));
            admin.setAccountType(com.campusconnect.domain.user.AccountType.FACULTY);
            admin.setGlobalRole(com.campusconnect.domain.user.GlobalRole.ADMIN);
            admin.setEmailVerified(true);
            mongo.insert(admin);

            Response res = login("Admin@DDU.ac.in", "AdminPass123");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.string("$.data.user.email")).isEqualTo("admin@ddu.ac.in");
            assertThat(res.string("$.data.user.globalRole")).isEqualTo("ADMIN");

            // Only this one address is exempt from the formats, and only for signing in.
            for (String accountType : List.of("STUDENT", "FACULTY")) {
                Response signup = register("accountType", accountType, "name", "Imposter", "email", "admin@ddu.ac.in", "password", "Secret123", "confirmPassword", "Secret123");
                assertThat(signup.status()).isEqualTo(400);
            }
            assertThat(login("administrator@ddu.ac.in", "AdminPass123").errorCode()).isEqualTo("INVALID_EMAIL");
        }

        @Test
        void rejectsAWrongPasswordWithAGenericMessage() {
            registerAndVerify();
            Response res = login(EMAIL, "Wrong1234");
            assertThat(res.status()).isEqualTo(401);
            assertThat(res.message()).isEqualTo("Invalid email or password");
        }

        @Test
        void rotatesRefreshTokensAndRevokesTheFamilyWhenAnOldTokenIsReplayed() {
            registerAndVerify();
            String firstCookie = login(EMAIL, "Secret123").cookie("refreshToken").orElseThrow();

            Response refreshed = api.cookie(firstCookie).post("/api/auth/refresh");
            assertThat(refreshed.status()).isEqualTo(200);
            String secondCookie = refreshed.cookie("refreshToken").orElseThrow();
            assertThat(secondCookie).isNotEqualTo(firstCookie);

            // Simulate a replay long after rotation (outside the concurrency grace window).
            mongo.updateMulti(Query.query(Criteria.where("revokedAt").ne(null)), new Update().set("revokedAt", Instant.now().minusSeconds(60)), "refreshtokens");

            assertThat(api.cookie(firstCookie).post("/api/auth/refresh").status()).isEqualTo(401);
            assertThat(api.cookie(secondCookie).post("/api/auth/refresh").status()).isEqualTo(401);
        }

        @Test
        void aConcurrentRefreshWithinTheGraceWindowDoesNotRevokeTheFamily() {
            registerAndVerify();
            String firstCookie = login(EMAIL, "Secret123").cookie("refreshToken").orElseThrow();
            String secondCookie = api.cookie(firstCookie).post("/api/auth/refresh").cookie("refreshToken").orElseThrow();

            // A second tab sends the old token right after the rotation: refused, but the new one keeps working.
            assertThat(api.cookie(firstCookie).post("/api/auth/refresh").status()).isEqualTo(401);
            assertThat(api.cookie(secondCookie).post("/api/auth/refresh").status()).isEqualTo(200);
        }

        @Test
        void logoutRevokesTheRefreshToken() {
            registerAndVerify();
            String cookie = login(EMAIL, "Secret123").cookie("refreshToken").orElseThrow();

            assertThat(api.cookie(cookie).post("/api/auth/logout").status()).isEqualTo(200);
            assertThat(api.cookie(cookie).post("/api/auth/refresh").status()).isEqualTo(401);
        }

        @Test
        void refreshAcceptsTheTokenInTheBodyAndExplainsMissingOrGarbageTokens() {
            registerAndVerify();
            String token = login(EMAIL, "Secret123").cookie("refreshToken").orElseThrow().substring("refreshToken=".length());

            assertThat(api.post("/api/auth/refresh", Map.of("refreshToken", token)).status()).isEqualTo(200);
            Response missing = api.post("/api/auth/refresh");
            assertThat(missing.status()).isEqualTo(401);
            assertThat(missing.message()).isEqualTo("Refresh token is required");
            Response garbage = api.post("/api/auth/refresh", Map.of("refreshToken", "not-a-token"));
            assertThat(garbage.status()).isEqualTo(401);
            assertThat(garbage.errorCode()).isEqualTo("TOKEN_INVALID");
        }

        @Test
        void changingThePasswordEndsEverySession() {
            registerAndVerify();
            Response login = login(EMAIL, "Secret123");
            String cookie = login.cookie("refreshToken").orElseThrow();

            Response wrong = api.withToken(login.string("$.data.accessToken")).post("/api/auth/change-password", Map.of("currentPassword", "Nope12345", "newPassword", "Better456"));
            assertThat(wrong.status()).isEqualTo(400);
            assertThat(wrong.errorCode()).isEqualTo("INVALID_CREDENTIALS");

            Response changed = api.withToken(login.string("$.data.accessToken")).post("/api/auth/change-password", Map.of("currentPassword", "Secret123", "newPassword", "Better456"));
            assertThat(changed.status()).isEqualTo(200);
            assertThat(api.cookie(cookie).post("/api/auth/refresh").status()).isEqualTo(401);
            assertThat(login(EMAIL, "Better456").status()).isEqualTo(200);
        }
    }

    @Nested
    class PasswordReset {

        static final String EMAIL = "24ceuog301@ddu.ac.in";

        Response requestReset(String address) {
            return api.post("/api/auth/forgot-password", Map.of("email", address));
        }

        Response confirmCode(String code) {
            return api.post("/api/auth/verify-reset-code", Map.of("email", EMAIL, "code", code));
        }

        @BeforeEach
        void registerNeha() {
            register("name", "Neha Joshi", "email", EMAIL, "password", "Secret123");
        }

        @Test
        void resetsThePasswordWithTheEmailedCodeAndInvalidatesSessions() {
            Response generic = requestReset("24ceuog999@ddu.ac.in");
            Response real = requestReset(EMAIL);
            assertThat(generic.status()).isEqualTo(200);
            assertThat(generic.body()).isEqualTo(real.body());

            DevInbox.Entry mail = data.lastMailTo(EMAIL);
            assertThat(mail.subject()).contains("password reset code");
            assertThat(mail.text()).doesNotContainPattern("https?://");
            String code = mail.code();

            Response confirmed = confirmCode(code);
            assertThat(confirmed.status()).isEqualTo(200);
            String resetToken = confirmed.string("$.data.resetToken");
            assertThat(resetToken).matches("^[a-f0-9]{64}$");

            // The code is spent once it has been exchanged.
            assertThat(confirmCode(code).errorCode()).isEqualTo("OTP_EXPIRED");

            assertThat(api.post("/api/auth/reset-password", Map.of("token", resetToken, "password", "NewSecret456")).status()).isEqualTo(200);
            assertThat(api.post("/api/auth/reset-password", Map.of("token", resetToken, "password", "Another789")).status()).isEqualTo(400);

            // Entering the code also proves the email, so an unverified account can now sign in.
            assertThat(api.post("/api/auth/login", Map.of("email", EMAIL, "password", "NewSecret456")).status()).isEqualTo(200);
        }

        @Test
        void aWrongResetCodeIsCountedAndNeverYieldsAResetToken() {
            requestReset(EMAIL);
            String code = code(EMAIL);
            String wrong = String.format("%06d", (Integer.parseInt(code) + 1) % 1_000_000);

            Response res = confirmCode(wrong);
            assertThat(res.errorCode()).isEqualTo("OTP_INVALID");
            assertThat(res.has("$.data")).isFalse();

            for (int i = 0; i < 4; i++) {
                confirmCode(wrong);
            }
            assertThat(confirmCode(code).errorCode()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        void aVerificationCodeCannotBeUsedAsAResetCode() {
            Response res = confirmCode(code(EMAIL));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.errorCode()).isEqualTo("OTP_EXPIRED");
        }

        @Test
        void theResetSessionExpires() {
            requestReset(EMAIL);
            String resetToken = confirmCode(code(EMAIL)).string("$.data.resetToken");
            setUser(EMAIL, new Update().set("passwordResetExpires", Instant.now().minusSeconds(1)));

            Response res = api.post("/api/auth/reset-password", Map.of("token", resetToken, "password", "NewSecret456"));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.errorCode()).isEqualTo("TOKEN_INVALID");
        }

        @Test
        void doesNotIssueAResetCodeToADisabledAccount() {
            setUser(EMAIL, new Update().set("isActive", false));
            int sentBefore = data.mailCount();
            requestReset(EMAIL);
            assertThat(data.mailCount()).isEqualTo(sentBefore);
        }
    }

    @Nested
    class EmailDeliveryWithoutSmtp {

        @Test
        void reportsPreviewModeAndExposesTheDevelopmentInboxOutsideProduction() {
            assertThat(api.get("/api/health").string("$.data.emailDelivery")).isEqualTo("preview");

            Response inbox = api.get("/api/dev/emails?to=24ceuog001@ddu.ac.in");
            assertThat(inbox.status()).isEqualTo(200);
            assertThat((List<?>) inbox.at("$.data")).isNotNull();
        }

        @Test
        void capturesMessagesWithTheirCodeInTheDevelopmentInbox() {
            register("name", "Asha", "email", "24ceuog001@ddu.ac.in", "password", "Secret123");
            Response inbox = api.get("/api/dev/emails?to=24CEUOG001@ddu.ac.in");
            assertThat(inbox.string("$.data[0].subject")).matches("\\d{6} is your CampusConnect verification code");
            assertThat(inbox.string("$.data[0].code")).matches("\\d{6}");
            assertThat((List<?>) inbox.at("$.data[0].links")).isEmpty();
        }
    }

    @Nested
    class ProtectedRoutes {

        @Test
        void rejectMissingAndForgedTokens() {
            assertThat(api.get("/api/notifications").status()).isEqualTo(401);
            Response forged = api.withToken("not-a-real-token").get("/api/notifications");
            assertThat(forged.status()).isEqualTo(401);
            assertThat(forged.errorCode()).isEqualTo("TOKEN_INVALID");
        }
    }
}
