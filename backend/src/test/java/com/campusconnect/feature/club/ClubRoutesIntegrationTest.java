package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventRegistration;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultStatus;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/** The club routes beyond the ported Node scenarios: directory, listings, response shapes, validation and permissions. */
class ClubRoutesIntegrationTest extends IntegrationTest {

    TestData.Venues venues;
    TestUser mentor;
    TestUser president;
    TestUser vice;
    TestUser member;
    TestUser outsider;
    TestUser admin;
    Club club;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        mentor = data.faculty("CE", "Mentor");
        president = data.student(s -> s.name("President"));
        vice = data.student(s -> s.name("Vice"));
        member = data.student(s -> s.name("Member"));
        outsider = data.student(s -> s.name("Outsider"));
        admin = data.admin();
        club = data.activeClub("Alpha Club", mentor, president);
        data.addMembership(club, vice, "VICE_PRESIDENT");
        data.addMembership(club, member);
    }

    String url(String path) {
        return "/api/clubs/" + club.getId() + path;
    }

    void setStatus(Club target, String status) {
        mongo.updateFirst(Query.query(Criteria.where("_id").is(target.getId())), new Update().set("status", status).set("statusNote", "Note for the admin"), Club.class);
    }

    Event event(String title, EventStatus status, Instant startAt) {
        Event event = new Event();
        event.setTitle(title);
        event.setShortDescription("A short description.");
        event.setDescription("A longer description of the event.");
        event.setCategory("TECHNOLOGY");
        event.setClub(club.getId());
        event.setVenue(venues.hall().getId());
        event.setStartAt(startAt);
        event.setEndAt(startAt.plus(2, ChronoUnit.HOURS));
        event.setRegistrationStart(Instant.now().minus(1, ChronoUnit.DAYS));
        event.setRegistrationEnd(startAt.minus(1, ChronoUnit.HOURS));
        event.setStatus(status);
        event.setCreatedBy(president.id());
        return mongo.insert(event);
    }

    RecruitmentDrive drive(RecruitmentStatus status, Instant start, Instant end) {
        RecruitmentPosition position = new RecruitmentPosition();
        position.setRole("MEMBER");
        position.setTitle("Member");
        RecruitmentDrive drive = new RecruitmentDrive();
        drive.setClub(club.getId());
        drive.setTitle("Spring intake");
        drive.setDescription("New members wanted.");
        drive.setPositions(List.of(position));
        drive.setApplicationStart(start);
        drive.setApplicationEnd(end);
        drive.setStatus(status);
        drive.setPublishedAt(status == RecruitmentStatus.PUBLISHED ? Instant.now() : null);
        drive.setCreatedBy(president.id());
        return mongo.insert(drive);
    }

    @Nested
    class Directory {

        @Test
        void listsActiveClubsByNameWithPaginationPopulatedPeopleAndCounts() {
            Club beta = data.activeClub("Beta Club", mentor, data.student());
            Club approved = data.activeClub("Gamma Club", mentor, data.student());
            setStatus(approved, "APPROVED");

            Response res = api.get("/api/clubs?limit=1");
            assertThat(res.status()).isEqualTo(200);
            assertThat(res.message()).isEqualTo("Clubs fetched");
            assertThat((Map<String, Object>) res.at("$.meta")).isEqualTo(Map.of("total", 2, "page", 1, "limit", 1, "totalPages", 2));
            assertThat(res.string("$.data[0].name")).isEqualTo("Alpha Club");
            assertThat(res.string("$.data[0].president.name")).isEqualTo("President");
            assertThat(res.has("$.data[0].president.phone")).isFalse();
            assertThat(res.string("$.data[0].mentor.name")).isEqualTo("Mentor");
            assertThat((Integer) res.at("$.data[0].memberCount")).isEqualTo(3);
            assertThat(res.has("$.data[0].recruiting")).isTrue();
            assertThat(res.has("$.data[0].statusNote")).isFalse();

            assertThat((List<String>) api.get("/api/clubs?page=2&limit=1").at("$.data[*].name")).containsExactly("Beta Club");
            assertThat((List<String>) api.get("/api/clubs?search=bet").at("$.data[*]._id")).containsExactly(beta.getId());
            assertThat((List<?>) api.get("/api/clubs?category=sports").at("$.data")).isEmpty();
        }

        @Test
        void onlyTheAdminBrowsesClubsThatAreNotActiveAndSeesStatusNotes() {
            Club paused = data.activeClub("Paused Club", mentor, data.student());
            setStatus(paused, "SUSPENDED");
            assertThat((List<String>) api.as(outsider).get("/api/clubs?status=ALL").at("$.data[*].name")).doesNotContain("Paused Club");

            assertThat((List<String>) api.as(admin).get("/api/clubs?status=ALL").at("$.data[*].name")).contains("Alpha Club", "Paused Club");
            Response suspended = api.as(admin).get("/api/clubs?status=suspended");
            assertThat((List<String>) suspended.at("$.data[*].name")).containsExactly("Paused Club");
            assertThat(suspended.string("$.data[0].statusNote")).isEqualTo("Note for the admin");
        }

        @Test
        void aPageReadBackFromTheSharedCacheIsIdenticalToAFreshOne() {
            drive(RecruitmentStatus.PUBLISHED, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(3, ChronoUnit.DAYS));
            Response fresh = api.as(outsider).get("/api/clubs");
            Response cached = api.as(outsider).get("/api/clubs");
            assertThat(cached.body()).isEqualTo(fresh.body());
            assertThat((Boolean) cached.at("$.data[0].recruiting.open")).isTrue();
            assertThat(cached.string("$.data[0].createdAt")).isEqualTo(fresh.string("$.data[0].createdAt"));
        }

        @Test
        void theSharedPageIsRefreshedWhenAClubOrItsMembersChange() {
            assertThat(api.get("/api/clubs").string("$.data[0].name")).isEqualTo("Alpha Club");
            assertThat(api.as(president).put(url(""), Map.of("name", "Alpha Robotics")).status()).isEqualTo(200);
            assertThat(api.get("/api/clubs").string("$.data[0].name")).isEqualTo("Alpha Robotics");

            assertThat(api.as(president).post(url("/members"), Map.of("userId", outsider.id())).status()).isEqualTo(201);
            assertThat((Integer) api.get("/api/clubs").at("$.data[0].memberCount")).isEqualTo(4);
        }

        @Test
        void recruitingShowsTheDriveTakingApplicationsAndWhetherItIsOpenYet() {
            RecruitmentDrive open = drive(RecruitmentStatus.PUBLISHED, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(3, ChronoUnit.DAYS));
            Response res = api.get("/api/clubs");
            assertThat(res.string("$.data[0].recruiting._id")).isEqualTo(open.getId());
            assertThat(res.string("$.data[0].recruiting.title")).isEqualTo("Spring intake");
            assertThat((Boolean) res.at("$.data[0].recruiting.open")).isTrue();

            mongo.updateFirst(Query.query(Criteria.where("_id").is(open.getId())), new Update().set("applicationStart", Instant.now().plus(1, ChronoUnit.DAYS)), RecruitmentDrive.class);
            // Drive writes clear the shared directory page (here: a club edit does).
            api.as(president).put(url(""), Map.of("tagline", "Upcoming intake"));
            assertThat((Boolean) api.get("/api/clubs").at("$.data[0].recruiting.open")).isFalse();
        }
    }

    @Nested
    class ClubPage {

        @Test
        void unknownAndMalformedIds() {
            Response unknown = api.get("/api/clubs/0123456789abcdef01234567");
            assertThat(unknown.status()).isEqualTo(404);
            assertThat(unknown.message()).isEqualTo("Club not found");
            Response malformed = api.get("/api/clubs/nope");
            assertThat(malformed.status()).isEqualTo(400);
            assertThat(malformed.message()).isEqualTo("Invalid id");
            assertThat(malformed.errorCode()).isEqualTo("VALIDATION_ERROR");
        }

        @Test
        void contactNumbersFollowWhoMayReachWhom() {
            Response anonymous = api.get(url(""));
            assertThat((Object) anonymous.at("$.data.viewer")).isNull();
            assertThat(anonymous.has("$.data.mentor.phone")).isFalse();
            assertThat(anonymous.has("$.data.president.phone")).isFalse();
            assertThat(anonymous.has("$.data.statusNote")).isFalse();

            Response insider = api.as(member).get(url(""));
            assertThat(insider.string("$.data.president.phone")).startsWith("+91");
            assertThat(insider.has("$.data.mentor.phone")).isTrue();
            assertThat(insider.has("$.data.statusNote")).isTrue();
            assertThat(insider.string("$.data.viewer.membershipStatus")).isEqualTo("APPROVED");
            assertThat((Boolean) insider.at("$.data.viewer.isMember")).isTrue();
            assertThat((Boolean) insider.at("$.data.viewer.isMentor")).isFalse();
        }

        @Test
        void countsUpcomingEventsAndShowsTheViewersApplications() {
            event("Next week", EventStatus.PUBLISHED, Instant.now().plus(7, ChronoUnit.DAYS));
            event("Draft", EventStatus.DRAFT, Instant.now().plus(7, ChronoUnit.DAYS));
            event("Past", EventStatus.PUBLISHED, Instant.now().minus(7, ChronoUnit.DAYS));
            RecruitmentDrive open = drive(RecruitmentStatus.PUBLISHED, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(3, ChronoUnit.DAYS));
            RecruitmentApplication application = new RecruitmentApplication();
            application.setDrive(open.getId());
            application.setClub(club.getId());
            application.setApplicant(outsider.id());
            application.setPosition(open.getPositions().getFirst().getId());
            application.setStatus(ApplicationStatus.APPLIED);
            mongo.insert(application);

            Response res = api.as(outsider).get(url(""));
            assertThat((Integer) res.at("$.data.upcomingEvents")).isEqualTo(1);
            assertThat(res.string("$.data.recruiting._id")).isEqualTo(open.getId());
            assertThat(res.string("$.data.viewer.applications[0].status")).isEqualTo("APPLIED");
            assertThat(res.string("$.data.viewer.applications[0].position")).isEqualTo(open.getPositions().getFirst().getId());
            assertThat((Object) res.at("$.data.viewer.membershipStatus")).isNull();
        }

        @Test
        void myClubsListsMembershipsWithTheirClubsAndTheClubsMentored() {
            Response mine = api.as(vice).get("/api/clubs/mine");
            assertThat(mine.message()).isEqualTo("Your clubs fetched");
            assertThat(mine.string("$.data.memberships[0].role")).isEqualTo("VICE_PRESIDENT");
            assertThat(mine.string("$.data.memberships[0].roleName")).isEqualTo("Vice-president");
            assertThat(mine.string("$.data.memberships[0].club.name")).isEqualTo("Alpha Club");
            assertThat((Map<String, Object>) mine.at("$.data.memberships[0].club.president")).isEqualTo(Map.of("_id", president.id(), "name", "President"));
            assertThat((List<?>) mine.at("$.data.mentored")).isEmpty();

            Response mentored = api.as(mentor).get("/api/memberships/me");
            assertThat(mentored.message()).isEqualTo("Your memberships fetched");
            assertThat(mentored.string("$.data.mentored[0].name")).isEqualTo("Alpha Club");
            assertThat((Integer) mentored.at("$.data.mentored[0].memberCount")).isEqualTo(3);
            assertThat(mentored.string("$.data.mentored[0].mentor.name")).isEqualTo("Mentor");

            assertThat(api.get("/api/clubs/mine").status()).isEqualTo(401);
        }
    }

    @Nested
    class EventsAndRecruitmentTabs {

        @Test
        void thePublicSeesPublishedEventsTheStaffSeeEveryStatus() {
            Event published = event("Hack Night", EventStatus.PUBLISHED, Instant.now().plus(5, ChronoUnit.DAYS));
            event("Workshop draft", EventStatus.DRAFT, Instant.now().plus(9, ChronoUnit.DAYS));
            EventRegistration registration = new EventRegistration();
            registration.setEvent(published.getId());
            registration.setUser(member.id());
            mongo.insert(registration);

            Response anonymous = api.get(url("/events"));
            assertThat(anonymous.message()).isEqualTo("Club events fetched");
            assertThat((List<String>) anonymous.at("$.data[*].title")).containsExactly("Hack Night");
            assertThat((Boolean) anonymous.at("$.meta.isStaff")).isFalse();
            assertThat((Integer) anonymous.at("$.meta.total")).isEqualTo(1);
            assertThat(anonymous.string("$.data[0].club.name")).isEqualTo("Alpha Club");
            assertThat(anonymous.string("$.data[0].venue.name")).isEqualTo("Seminar Hall A");
            assertThat(anonymous.string("$.data[0].createdBy.name")).isEqualTo("President");
            assertThat(anonymous.string("$.data[0].registrationState")).isEqualTo("OPEN");
            assertThat(anonymous.has("$.data[0].budgetItems")).isFalse();
            assertThat(anonymous.has("$.data[0].myRegistration")).isFalse();

            Response staff = api.as(president).get(url("/events"));
            assertThat((Boolean) staff.at("$.meta.isStaff")).isTrue();
            assertThat((List<String>) staff.at("$.data[*].title")).containsExactly("Workshop draft", "Hack Night");
            assertThat((List<String>) api.as(mentor).get(url("/events?status=draft")).at("$.data[*].title")).containsExactly("Workshop draft");
            assertThat((List<String>) api.as(outsider).get(url("/events?status=DRAFT")).at("$.data[*].title")).containsExactly("Hack Night");

            Response viewer = api.as(member).get(url("/events"));
            assertThat(viewer.string("$.data[0].myRegistration")).isEqualTo("REGISTERED");
            assertThat((Boolean) viewer.at("$.data[0].followingClub")).isTrue();
            assertThat((Boolean) viewer.at("$.data[0].likedByMe")).isFalse();
        }

        @Test
        void publishedResultsAreAttachedToTheirEvents() {
            Event done = event("Code Sprint", EventStatus.COMPLETED, Instant.now().minus(3, ChronoUnit.DAYS));
            ResultAward award = new ResultAward();
            award.setTitle("Winner");
            award.setPosition(1);
            award.setRecipientUser(member.id());
            award.setRecipientName("Member");
            EventResult result = new EventResult();
            result.setEvent(done.getId());
            result.setClub(club.getId());
            result.setSummary("Twelve teams took part.");
            result.setAwards(List.of(award));
            result.setStatus(ResultStatus.PUBLISHED);
            result.setPublishedAt(Instant.now());
            mongo.insert(result);

            Response res = api.get(url("/events"));
            assertThat(res.string("$.data[0].resultStage")).isEqualTo("final");
            assertThat(res.string("$.data[0].result.summary")).isEqualTo("Twelve teams took part.");
            assertThat(res.string("$.data[0].result.awards[0].recipientUser.name")).isEqualTo("Member");
            assertThat(res.string("$.data[0].registrationState")).isEqualTo("UNAVAILABLE");
            assertThat((Object) res.at("$.data[0].latestRound")).isNull();
        }

        @Test
        void thePublicSeesPublishedDrivesThePresidentAndMentorSeeAllWithCounts() {
            RecruitmentDrive published = drive(RecruitmentStatus.PUBLISHED, Instant.now().minus(1, ChronoUnit.HOURS), Instant.now().plus(3, ChronoUnit.DAYS));
            drive(RecruitmentStatus.DRAFT, Instant.now(), Instant.now().plus(5, ChronoUnit.DAYS));
            RecruitmentApplication application = new RecruitmentApplication();
            application.setDrive(published.getId());
            application.setClub(club.getId());
            application.setApplicant(outsider.id());
            application.setPosition(published.getPositions().getFirst().getId());
            mongo.insert(application);

            Response visitor = api.get(url("/recruitment"));
            assertThat(visitor.message()).isEqualTo("Recruitment fetched");
            assertThat((Boolean) visitor.at("$.data.canCreate")).isFalse();
            assertThat((List<String>) visitor.at("$.data.items[*].status")).containsExactly("PUBLISHED");
            assertThat(visitor.string("$.data.items[0].phase")).isEqualTo("OPEN");
            assertThat((List<String>) visitor.at("$.data.items[0].positions")).containsExactly("Member");
            assertThat(visitor.has("$.data.items[0].counts")).isFalse();

            Response staff = api.as(president).get(url("/recruitment"));
            assertThat((List<String>) staff.at("$.data.items[*].status")).containsExactlyInAnyOrder("PUBLISHED", "DRAFT");
            assertThat((Boolean) staff.at("$.data.canCreate")).isFalse();
            List<Map<String, Object>> counts = staff.at("$.data.items[?(@.status == 'PUBLISHED')].counts");
            assertThat(counts.getFirst()).containsEntry("total", 1).containsEntry("active", 1);
            assertThat((Map<String, Object>) counts.getFirst().get("byPosition")).containsKey(published.getPositions().getFirst().getId());
            assertThat(api.as(mentor).get(url("/recruitment")).has("$.data.items[0].counts")).isTrue();
            assertThat(api.get("/api/clubs/0123456789abcdef01234567/recruitment").status()).isEqualTo(404);
        }
    }

    @Nested
    class ValidationAndPermissions {

        @Test
        void profileEditsReportEveryBrokenRuleInOrder() {
            Response res = api.as(president).put(url(""), Map.of("name", "ab", "logo", "ftp://logo.png", "category", "PARTY"));
            assertThat(res.status()).isEqualTo(400);
            assertThat(res.message()).isEqualTo("Invalid value. Invalid category. logo must be an http(s) URL or an uploaded file");
            assertThat(api.as(president).put("/api/clubs/nope", Map.of("name", "ab")).message()).isEqualTo("Invalid id. Invalid value");
            Response taken = api.as(president).put(url(""), Map.of("name", data.activeClub("Taken Name", mentor, data.student()).getName().toUpperCase()));
            assertThat(taken.status()).isEqualTo(409);
            assertThat(taken.message()).isEqualTo("Another club already uses this name");
        }

        @Test
        void governanceRoutesCheckTheirBodiesAndRoles() {
            assertThat(api.as(admin).post(url("/status"), Map.of("status", "FOO")).message()).isEqualTo("Choose active, suspended or archived");
            Response notAdmin = api.as(mentor).post(url("/status"), Map.of("status", "SUSPENDED", "reason", "Because"));
            assertThat(notAdmin.status()).isEqualTo(403);
            assertThat(notAdmin.message()).isEqualTo("You do not have permission to perform this action");
            assertThat(api.as(admin).put(url("/mentor"), Map.of("mentorId", "x")).message()).isEqualTo("A valid mentorId is required");
            assertThat(api.as(admin).put(url("/mentor"), Map.of("mentorId", outsider.id())).message()).isEqualTo("Mentor must be an active faculty member");
            assertThat(api.as(mentor).post(url("/president"), Map.of()).message()).isEqualTo("A valid userId is required");
            assertThat(api.as(mentor).post(url("/president"), Map.of("userId", mentor.id())).message()).isEqualTo("President must be an active, verified student");
            assertThat(api.as(mentor).post(url("/president"), Map.of("userId", president.id())).message()).isEqualTo("This student is already the president");

            setStatus(club, "ARCHIVED");
            Response archived = api.as(mentor).post(url("/president"), Map.of("userId", vice.id()));
            assertThat(archived.status()).isEqualTo(409);
            assertThat(archived.errorCode()).isEqualTo("INVALID_STATE");
        }

        @Test
        void memberRoutesCheckTheirInputAndWhoIsActing() {
            assertThat(api.as(president).patch(url("/members/" + member.id() + "/role"), Map.of()).message()).isEqualTo("Invalid value. Choose a role");
            assertThat(api.as(president).patch(url("/members/nope/role"), Map.of("role", "MEMBER")).message()).isEqualTo("Invalid userId");
            assertThat(api.as(president).patch(url("/members/" + president.id() + "/role"), Map.of("role", "TREASURER")).message())
                    .isEqualTo("You cannot change your own club role");
            assertThat(api.as(president).patch(url("/members/" + outsider.id() + "/role"), Map.of("role", "TREASURER")).message())
                    .isEqualTo("This user is not a member of the club");

            // The vice-president manages members but only the president removes officers.
            data.addMembership(club, outsider, "TREASURER");
            Response officer = api.as(vice).delete(url("/members/" + outsider.id()));
            assertThat(officer.status()).isEqualTo(403);
            assertThat(officer.message()).isEqualTo("Only the president can remove club officers");
            assertThat(api.as(vice).delete(url("/members/" + member.id())).status()).isEqualTo(200);
            assertThat(api.as(member).post(url("/leave")).message()).isEqualTo("You are not a member of this club");

            assertThat(api.as(president).post(url("/members"), Map.of("userId", mentor.id())).message()).isEqualTo("Only verified students can be added");
            setStatus(club, "SUSPENDED");
            Response paused = api.as(president).post(url("/members"), Map.of("userId", data.student().id()));
            assertThat(paused.status()).isEqualTo(409);
            assertThat(paused.errorCode()).isEqualTo("CLUB_NOT_ACTIVE");
            assertThat(api.get(url("/members")).status()).isEqualTo(401);
        }

        @Test
        void roleRoutesValidateNamesAndAuthorities() {
            Response missing = api.as(president).post(url("/roles"), Map.of());
            assertThat(missing.message()).isEqualTo("Invalid value. Role names are 2 to 40 characters. Choose the role's authorities");
            assertThat(api.as(president).post(url("/roles"), Map.of("name", "Scribe", "permissions", List.of("FLY"))).message()).isEqualTo("Unknown authority");
            assertThat(api.as(president).put(url("/roles/NOPE"), Map.of("description", "x")).status()).isEqualTo(404);
            assertThat(api.as(president).delete(url("/roles/VICE_PRESIDENT")).message()).isEqualTo("The vice-president role is built in and can't be deleted");
            assertThat(api.as(outsider).get(url("/roles")).message()).isEqualTo("Only club members and the club's mentor can see its roles");
            assertThat(api.as(president).post(url("/president/transfer"), Map.of("userId", president.id())).message()).isEqualTo("Choose another member");
        }

        @Test
        void theBellNeedsAKnownClub() {
            assertThat(api.as(member).get("/api/clubs/0123456789abcdef01234567/subscription").status()).isEqualTo(404);
            assertThat(api.as(member).put("/api/clubs/nope/subscription", Map.of("enabled", "x")).message()).isEqualTo("Invalid id. enabled must be true or false");
        }
    }
}
