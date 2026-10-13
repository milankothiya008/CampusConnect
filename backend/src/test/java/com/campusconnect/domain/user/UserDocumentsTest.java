package com.campusconnect.domain.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import java.util.Date;
import java.util.List;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class UserDocumentsTest extends MongoDocumentTest {

    @Autowired
    UserRepository users;

    @Autowired
    RefreshTokenRepository tokens;

    private static User user(String email) {
        User user = new User();
        user.setName("Asha Patel");
        user.setEmail(email);
        user.setPassword("$2a$12$hash");
        user.setAccountType(AccountType.STUDENT);
        return user;
    }

    private static User fullUser() {
        User user = user("23ce001@uni.edu");
        user.setGlobalRole(GlobalRole.ADMIN);
        user.setDepartmentCode("CE");
        user.setBatchCode("23");
        user.setAvatar("https://cdn.example/a.png");
        user.setPhone("+919876543210");
        user.setEmailVerified(true);
        user.setEmailVerificationCodeHash("evh");
        user.setEmailVerificationCodeExpires(at(10));
        user.setEmailVerificationAttempts(2);
        user.setEmailVerificationSentAt(at(0));
        user.setPasswordResetCodeHash("prh");
        user.setPasswordResetCodeExpires(at(20));
        user.setPasswordResetAttempts(1);
        user.setPasswordResetSentAt(at(15));
        user.setPasswordResetTokenHash("prt");
        user.setPasswordResetExpires(at(30));
        user.setActive(false);
        user.getEmailPreferences().setClubUpdates(false);
        user.getEmailPreferences().setRecruitment(false);
        user.setLastSeenAt(at(-1));
        user.setShowActivityStatus(false);
        user.setChatNotifications(false);
        user.setBlockedUsers(List.of(newId(), newId()));
        user.setCalendarKeyVersion(3);
        return user;
    }

    @Test
    void userRoundTrip() {
        User user = users.save(fullUser());

        assertSameDocument(users.findById(user.getId()).orElseThrow(), user);
        Document raw = raw("users");
        assertObjectIds(raw, "_id", "blockedUsers.0", "blockedUsers.1");
        // "is…" booleans keep their Mongoose names in MongoDB as well.
        assertThat(raw.getBoolean("isActive")).isFalse();
        assertThat(raw.getBoolean("isEmailVerified")).isTrue();
        assertAbsent(raw, "active", "emailVerified", "emailPreferences._id");
        assertThat(raw.getString("password")).isEqualTo("$2a$12$hash"); // stored, never serialised
        assertThat(raw.getInteger("calendarKeyVersion")).isEqualTo(3);
        assertThat(users.findByEmail("23ce001@uni.edu")).isPresent();
        assertThat(users.existsByEmail("23ce001@uni.edu")).isTrue();
    }

    @Test
    void userJsonKeepsIsNamesAndHidesSecrets() {
        User user = users.save(fullUser());

        JsonNode json = toJson(user);

        assertThat(json.get("_id").stringValue()).isEqualTo(user.getId());
        assertThat(json.get("isActive").booleanValue()).isFalse();
        assertThat(json.get("isEmailVerified").booleanValue()).isTrue();
        assertThat(json.has("active")).isFalse();
        assertThat(json.has("emailVerified")).isFalse();
        assertThat(json.propertyNames()).doesNotContain("id", "password", "emailVerificationCodeHash",
                "emailVerificationCodeExpires", "emailVerificationAttempts", "emailVerificationSentAt",
                "passwordResetCodeHash", "passwordResetCodeExpires", "passwordResetAttempts", "passwordResetSentAt",
                "passwordResetTokenHash", "passwordResetExpires", "calendarKeyVersion");
        assertThat(json.get("emailPreferences").get("clubUpdates").booleanValue()).isFalse();
        assertThat(json.get("blockedUsers").get(0).stringValue()).isEqualTo(user.getBlockedUsers().get(0));
        assertThat(json.get("globalRole").stringValue()).isEqualTo("ADMIN");
    }

    @Test
    void newUserHasTheMongooseDefaults() {
        User user = users.save(user("24it007@uni.edu"));

        Document raw = raw("users");
        assertThat(raw.getString("globalRole")).isEqualTo("STUDENT");
        assertThat(raw.getBoolean("isActive")).isTrue();
        assertThat(raw.getBoolean("isEmailVerified")).isFalse();
        assertAbsent(raw, "departmentCode", "phone", "lastSeenAt");
        JsonNode json = toJson(user);
        assertThat(json.get("isActive").booleanValue()).isTrue();
        assertThat(json.get("phone").isNull()).isTrue();
        assertThat(json.get("emailPreferences").get("recruitment").booleanValue()).isTrue();
    }

    @Test
    void readsAUserAsTheNodeAppStoredIt() {
        ObjectId id = new ObjectId();
        ObjectId blocked = new ObjectId();
        mongo.getCollection("users").insertOne(new Document("_id", id)
                .append("name", "Asha")
                .append("email", "23ce001@uni.edu")
                .append("password", "$2a$12$hash")
                .append("accountType", "STUDENT")
                .append("globalRole", "FACULTY")
                .append("departmentCode", "CE")
                .append("batchCode", null)
                .append("isEmailVerified", true)
                .append("emailVerificationCodeHash", null)
                .append("emailVerificationAttempts", 0)
                .append("isActive", true)
                .append("emailPreferences", new Document("clubUpdates", false).append("eventRecommendations", true)
                        .append("eventActivity", true).append("recruitment", true))
                .append("blockedUsers", List.of(blocked))
                .append("calendarKeyVersion", 2)
                .append("createdAt", Date.from(at(0)))
                .append("updatedAt", Date.from(at(1)))
                .append("__v", 0));

        User user = users.findById(id.toHexString()).orElseThrow();

        assertThat(user.isEmailVerified()).isTrue();
        assertThat(user.isActive()).isTrue();
        assertThat(user.getGlobalRole()).isEqualTo(GlobalRole.FACULTY);
        assertThat(user.getBlockedUsers()).containsExactly(blocked.toHexString());
        assertThat(user.getEmailPreferences().isClubUpdates()).isFalse();
        assertThat(user.getCalendarKeyVersion()).isEqualTo(2);
        assertThat(user.isShowActivityStatus()).isTrue(); // missing path: default
    }

    @Test
    void emailsAreUnique() {
        users.save(user("23ce001@uni.edu"));

        assertThatThrownBy(() -> users.save(user("23ce001@uni.edu"))).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void refreshTokenRoundTrip() {
        RefreshToken token = new RefreshToken();
        token.setUser(newId());
        token.setTokenHash("sha256-a");
        token.setExpiresAt(at(10_000));
        token.setRevokedAt(at(5));
        token.setReplacedByTokenHash("sha256-b");
        token.setUserAgent("Firefox");
        tokens.save(token);

        assertSameDocument(tokens.findByTokenHash("sha256-a").orElseThrow(), token);
        assertObjectIds(raw("refreshtokens"), "_id", "user");
        assertDates(raw("refreshtokens"), "expiresAt");
        assertThat(toJson(token).get("_id").stringValue()).isEqualTo(token.getId());

        RefreshToken again = new RefreshToken();
        again.setUser(newId());
        again.setTokenHash("sha256-a");
        again.setExpiresAt(at(10_000));
        assertThatThrownBy(() -> tokens.save(again)).isInstanceOf(DuplicateKeyException.class);
    }
}
