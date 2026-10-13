package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.recruitment.PositionForm;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.user.User;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of tests/integration/clubRoles.test.js. Where the Node test proves a role's authority by creating an event
 * (the events feature), this one asks {@link ClubAccess} — the check the events routes use. The start-up migration
 * test is not ported.
 */
class ClubRolesIntegrationTest extends IntegrationTest {

    @Autowired
    ClubAccess access;

    @Nested
    class ClubDefinedRoles {

        Club club;
        TestUser mentor;
        TestUser president;
        TestUser vice;
        TestUser member;
        TestUser designer;
        TestUser other;

        @BeforeEach
        void seed() {
            data.seedReferenceData();
            mentor = data.faculty();
            president = data.student(s -> s.name("President"));
            vice = data.student(s -> s.name("Vice"));
            member = data.student(s -> s.name("Plain Member"));
            designer = data.student(s -> s.name("Dia Designer"));
            other = data.student(s -> s.name("Other Member"));
            club = data.activeClub("Robotics", mentor, president);
            data.addMembership(club, vice, "VICE_PRESIDENT");
            data.addMembership(club, member);
            data.addMembership(club, designer);
            data.addMembership(club, other);
        }

        String url(String path) {
            return "/api/clubs/" + club.getId() + path;
        }

        Response roles(TestUser user) {
            return api.as(user).get(url("/roles"));
        }

        Map<String, Object> designBody() {
            return Map.of("name", "Design lead", "description", "Posters and reels", "permissions", List.of("POST_UPDATES", "MODERATE_GALLERY"));
        }

        String createDesignRole() {
            Response created = api.as(president).post(url("/roles"), designBody());
            assertThat(created.status()).as(created.body()).isEqualTo(201);
            List<String> keys = created.at("$.data.roles[?(@.name == 'Design lead')].key");
            return keys.getFirst();
        }

        AuthUser authUser(TestUser user) {
            return AuthUser.of(mongo.findById(user.id(), User.class));
        }

        @Test
        void membersAndTheMentorSeeTheClubsRolesEveryoneStartsWithTheDefaults() {
            Response res = roles(member);
            assertThat(res.status()).isEqualTo(200);
            assertThat((Boolean) res.at("$.data.canManage")).isFalse();
            assertThat((List<String>) res.at("$.data.roles[*].name"))
                    .containsExactly("President", "Vice-president", "Event coordinator", "Marketing coordinator", "Technical coordinator", "Treasurer", "Member");
            Map<String, Object> vp = ((List<Map<String, Object>>) res.at("$.data.roles[?(@.key == 'VICE_PRESIDENT')]")).getFirst();
            assertThat(vp).containsEntry("system", true).containsEntry("unique", true).containsEntry("editable", true)
                    .containsEntry("renamable", false).containsEntry("deletable", false);
            assertThat((Map<String, Object>) vp.get("holder")).containsEntry("name", "Vice");
            assertThat(roles(mentor).status()).isEqualTo(200);
            assertThat(roles(data.student()).status()).isEqualTo(403);
        }

        @Test
        void onlyThePresidentCreatesRolesAndPresidentOnlyAuthoritiesCantBeGiven() {
            assertThat(api.as(vice).post(url("/roles"), designBody()).status()).isEqualTo(403);
            Response refused = api.as(president).post(url("/roles"), Map.of("name", "Design lead", "description", "Posters and reels",
                    "permissions", List.of("POST_UPDATES", "MANAGE_RECRUITMENT")));
            assertThat(refused.status()).isEqualTo(400);
            assertThat(refused.message()).contains("stay with the president");

            Response created = api.as(president).post(url("/roles"), designBody());
            assertThat(created.status()).isEqualTo(201);
            Map<String, Object> design = ((List<Map<String, Object>>) created.at("$.data.roles[?(@.name == 'Design lead')]")).getFirst();
            assertThat((List<String>) design.get("permissions")).containsExactly("POST_UPDATES", "MODERATE_GALLERY");
            assertThat(design).containsEntry("system", false).containsEntry("deletable", true);
            assertThat((String) design.get("key")).matches("^R_[a-f0-9]{24}$");
            assertThat(api.as(president).post(url("/roles"), Map.of("name", "design LEAD", "description", "Posters and reels",
                    "permissions", List.of("POST_UPDATES", "MODERATE_GALLERY"))).status()).isEqualTo(409);
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("CLUB_ROLE_CREATED")), "auditlogs")).isTrue();
        }

        @Test
        void aCustomRolesAuthoritiesTakeEffectForWhoeverHoldsItAndChangeWhenTheRoleChanges() {
            String key = createDesignRole();
            Response assigned = api.as(president).patch(url("/members/" + designer.id() + "/role"), Map.of("role", key));
            assertThat(assigned.status()).isEqualTo(200);
            assertThat(assigned.string("$.data.roleName")).isEqualTo("Design lead");
            Map<String, Object> notification = mongo.findOne(Query.query(Criteria.where("user").is(new ObjectId(designer.id())).and("type").is("CLUB_ROLE_CHANGED")),
                    Map.class, "notifications");
            assertThat(notification).containsEntry("message", "You are now Design lead.");

            Response detail = api.as(designer).get(url(""));
            assertThat(detail.string("$.data.viewer.roleName")).isEqualTo("Design lead");
            assertThat((List<String>) detail.at("$.data.viewer.permissions")).containsExactly("POST_UPDATES", "MODERATE_GALLERY");
            assertThatThrownBy(() -> access.assertPermission(authUser(designer), club.getId(), ClubPermission.MANAGE_EVENTS)).isInstanceOf(AppException.class);

            api.as(president).put(url("/roles/" + key), Map.of("permissions", List.of("POST_UPDATES", "MANAGE_EVENTS")));
            detail = api.as(designer).get(url(""));
            assertThat((List<String>) detail.at("$.data.viewer.permissions")).containsExactly("POST_UPDATES", "MANAGE_EVENTS");
            assertThat(access.context(authUser(designer), club.getId()).has(ClubPermission.MANAGE_EVENTS)).isTrue();

            Response mine = api.as(designer).get("/api/clubs/mine");
            assertThat(mine.string("$.data.memberships[0].roleName")).isEqualTo("Design lead");
            assertThat((List<String>) mine.at("$.data.memberships[0].permissions")).containsExactly("POST_UPDATES", "MANAGE_EVENTS");
        }

        @Test
        void builtInRolesThePresidentAndMemberRolesAreFixedTheVicePresidentsAuthoritiesCanChange() {
            assertThat(api.as(president).put(url("/roles/PRESIDENT"), Map.of("permissions", List.of())).status()).isEqualTo(409);
            assertThat(api.as(president).delete(url("/roles/MEMBER")).status()).isEqualTo(409);
            Response vp = api.as(president).put(url("/roles/VICE_PRESIDENT"), Map.of("name", "Deputy", "permissions", List.of("MANAGE_EVENTS")));
            assertThat(vp.status()).isEqualTo(200);
            Map<String, Object> updated = ((List<Map<String, Object>>) vp.at("$.data.roles[?(@.key == 'VICE_PRESIDENT')]")).getFirst();
            assertThat(updated).containsEntry("name", "Vice-president");
            assertThat((List<String>) updated.get("permissions")).containsExactly("MANAGE_EVENTS");
            assertThat((List<String>) api.as(vice).get(url("")).at("$.data.viewer.permissions")).containsExactly("MANAGE_EVENTS");
        }

        @Test
        void oneVicePresidentPerClubNobodyIsMadePresidentByARoleChange() {
            Response clash = api.as(president).patch(url("/members/" + other.id() + "/role"), Map.of("role", "VICE_PRESIDENT"));
            assertThat(clash.status()).isEqualTo(409);
            assertThat(clash.message()).contains("Vice is already vice-president");
            assertThat(api.as(president).patch(url("/members/" + other.id() + "/role"), Map.of("role", "PRESIDENT")).status()).isEqualTo(400);
            assertThat(api.as(president).patch(url("/members/" + other.id() + "/role"), Map.of("role", "R_000000000000000000000000")).message()).contains("no such role");

            // The database refuses a second vice-president too.
            assertThatThrownBy(() -> mongo.updateFirst(Query.query(Criteria.where("club").is(club.getId()).and("user").is(other.id())),
                    new Update().set("role", "VICE_PRESIDENT"), ClubMembership.class)).isInstanceOf(DuplicateKeyException.class);
        }

        @Test
        void deletingARoleMakesItsHoldersMembersNotWhileRecruitmentUsesIt() {
            String key = createDesignRole();
            api.as(president).patch(url("/members/" + designer.id() + "/role"), Map.of("role", key));

            RecruitmentPosition position = new RecruitmentPosition();
            position.setRole(key);
            position.setTitle("Design lead");
            position.setForm(new PositionForm());
            RecruitmentDrive drive = new RecruitmentDrive();
            drive.setClub(club.getId());
            drive.setTitle("Design team");
            drive.setDescription("Join");
            drive.setPositions(List.of(position));
            drive.setApplicationStart(Instant.now());
            drive.setApplicationEnd(Instant.now().plus(1, ChronoUnit.DAYS));
            drive.setCreatedBy(president.id());
            drive = mongo.insert(drive);
            assertThat(api.as(president).delete(url("/roles/" + key)).message()).contains("\"Design team\" is recruiting for this role");
            mongo.remove(drive);

            Response deleted = api.as(president).delete(url("/roles/" + key));
            assertThat(deleted.status()).isEqualTo(200);
            assertThat((List<String>) deleted.at("$.data.roles[*].key")).doesNotContain(key);
            ClubMembership membership = mongo.findOne(Query.query(Criteria.where("club").is(club.getId()).and("user").is(designer.id())), ClubMembership.class);
            assertThat(membership.getRole()).isEqualTo("MEMBER");
            assertThat(mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(designer.id())).and("message").regex("\"Design lead\" role was removed")), "notifications")).isTrue();
        }

        @Test
        void thePresidentHandsOverThePresidencyTheOldPresidentBecomesAMember() {
            assertThat(api.as(vice).post(url("/president/transfer"), Map.of("userId", member.id())).status()).isEqualTo(403);
            assertThat(api.as(president).post(url("/president/transfer"), Map.of("userId", data.student().id())).status()).isEqualTo(400);
            assertThat(api.as(president).post(url("/leave")).message()).contains("Hand over the presidency");

            Response res = api.as(president).post(url("/president/transfer"), Map.of("userId", vice.id()));
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Vice is now president");

            assertThat(roleOf(vice)).isEqualTo("PRESIDENT");
            assertThat(roleOf(president)).isEqualTo("MEMBER");
            assertThat(mongo.findById(club.getId(), Club.class).getPresident()).isEqualTo(vice.id());
            assertThat(mongo.exists(Query.query(Criteria.where("user").is(new ObjectId(mentor.id())).and("title").is("New president for Robotics")), "notifications")).isTrue();
            assertThat(mongo.exists(Query.query(Criteria.where("action").is("PRESIDENCY_TRANSFERRED")), "auditlogs")).isTrue();

            // The vice-president seat is free again, and the new president runs the roles now.
            assertThat(api.as(vice).patch(url("/members/" + other.id() + "/role"), Map.of("role", "VICE_PRESIDENT")).status()).isEqualTo(200);
            assertThat(api.as(president).post(url("/roles"), Map.of("name", "Advisor", "permissions", List.of())).status()).isEqualTo(403);
            assertThat(api.as(president).post(url("/leave")).status()).isEqualTo(200);
        }

        String roleOf(TestUser user) {
            return mongo.findOne(Query.query(Criteria.where("club").is(club.getId()).and("user").is(user.id())), ClubMembership.class).getRole();
        }
    }
}
