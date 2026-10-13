package com.campusconnect.feature.membership;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.events.ClubMembershipChanged;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;

/** /api/memberships and the member routes' responses, side effects and races. */
@RecordApplicationEvents
class MembershipRoutesIntegrationTest extends IntegrationTest {

    @Autowired
    ApplicationEvents events;

    TestUser mentor;
    TestUser president;
    TestUser member;
    TestUser student;
    TestUser admin;
    Club club;

    @BeforeEach
    void seed() {
        data.seedReferenceData();
        mentor = data.faculty();
        president = data.student(s -> s.name("President"));
        member = data.student(s -> s.name("Member"));
        student = data.student(s -> s.name("Student"));
        admin = data.admin();
        club = data.activeClub("Photo Club", mentor, president);
        data.addMembership(club, member);
    }

    String url(String path) {
        return "/api/clubs/" + club.getId() + path;
    }

    @Test
    void aUsersClubsAreForThemselvesAndTheAdmin() {
        Response mine = api.as(member).get("/api/memberships/users/" + member.id() + "/clubs");
        assertThat(mine.message()).isEqualTo("User clubs fetched");
        assertThat((Integer) mine.at("$.meta.total")).isEqualTo(1);
        assertThat((Map<String, Object>) mine.at("$.data[0].club")).containsEntry("name", "Photo Club").containsEntry("status", "ACTIVE")
                .containsKeys("_id", "category", "logo");
        assertThat(mine.string("$.data[0].role")).isEqualTo("MEMBER");
        assertThat(api.as(admin).get("/api/memberships/users/" + member.id() + "/clubs").status()).isEqualTo(200);
        assertThat(api.as(student).get("/api/memberships/users/" + member.id() + "/clubs").message()).isEqualTo("You cannot view another user's clubs");
        assertThat(api.as(student).get("/api/memberships/users/nope/clubs").message()).isEqualTo("Invalid userId");
    }

    @Test
    void addingAndRoleChangesReturnTheMembershipWithItsUser() {
        Response added = api.as(president).post(url("/members"), Map.of("userId", student.id()));
        assertThat(added.message()).isEqualTo("Member added");
        assertThat((Map<String, Object>) added.at("$.data.user")).containsEntry("_id", student.id()).containsEntry("name", "Student")
                .containsKeys("email", "departmentCode", "batchCode").doesNotContainKey("phone");
        assertThat(added.string("$.data.decidedBy")).isEqualTo(president.id());
        assertThat(mongo.exists(Query.query(Criteria.where("action").is("MEMBERSHIP_APPROVED").and("metadata.direct").is(true)), "auditlogs")).isTrue();

        Response changed = api.as(president).patch(url("/members/" + student.id() + "/role"), Map.of("role", " TREASURER "));
        assertThat(changed.message()).isEqualTo("Member role updated");
        assertThat(changed.string("$.data.role")).isEqualTo("TREASURER");
        assertThat(changed.string("$.data.roleName")).isEqualTo("Treasurer");
        assertThat(changed.string("$.data.user.name")).isEqualTo("Student");
        assertThat(mongo.exists(Query.query(Criteria.where("action").is("MEMBER_ROLE_CHANGED").and("fromState").is("MEMBER").and("toState").is("TREASURER")), "auditlogs")).isTrue();
    }

    @Test
    void aStudentWhoseOldRequestWasClosedCanBeAddedAgain() {
        ClubMembership old = new ClubMembership();
        old.setClub(club.getId());
        old.setUser(student.id());
        old.setStatus(MembershipStatus.REJECTED);
        mongo.insert(old);
        assertThat(api.as(president).post(url("/members"), Map.of("userId", student.id())).string("$.data.status")).isEqualTo("APPROVED");
        assertThat(mongo.count(Query.query(Criteria.where("club").is(club.getId()).and("user").is(student.id())), ClubMembership.class)).isEqualTo(1);
    }

    @Test
    void twoAddsAtOnceLeaveOneMembership() throws Exception {
        List<Callable<Response>> adds = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            adds.add(() -> api.as(president).post(url("/members"), Map.of("userId", student.id())));
        }
        List<Integer> statuses = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(6)) {
            for (Future<Response> future : pool.invokeAll(adds)) {
                statuses.add(future.get().status());
            }
        }
        assertThat(statuses).containsOnly(201, 409);
        assertThat(statuses.stream().filter(status -> status == 201)).hasSize(1);
        assertThat(mongo.count(Query.query(Criteria.where("club").is(club.getId()).and("user").is(student.id())), ClubMembership.class)).isEqualTo(1);
    }

    @Test
    void leavingAndRemovalTellTheChatFeature() {
        data.addMembership(club, student);
        assertThat(api.as(member).post(url("/leave")).message()).isEqualTo("You left the club");
        assertThat(api.as(president).delete(url("/members/" + student.id())).message()).isEqualTo("Member removed");
        assertThat(events.stream(ClubMembershipChanged.class)).containsExactly(new ClubMembershipChanged(club.getId(), member.id()),
                new ClubMembershipChanged(club.getId(), student.id()));
        assertThat(mongo.exists(Query.query(Criteria.where("action").is("MEMBER_LEFT").and("fromState").is("APPROVED")), "auditlogs")).isTrue();
    }

    /** The club group chat caches its member list; a new member is announced so they are in it at once. */
    @Test
    void addingAMemberRefreshesTheClubChatMembers() {
        api.as(president).post(url("/members"), Map.of("userId", student.id()));
        assertThat(events.stream(ClubMembershipChanged.class))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.clubId()).isEqualTo(club.getId());
                    assertThat(event.userId()).isEqualTo(student.id());
                });
    }
}
