package com.campusconnect.feature.event;

import static com.campusconnect.feature.event.EventFlows.map;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.user.User;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.feature.venue.VenueService;
import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestData;
import com.campusconnect.support.TestUser;
import java.util.List;
import java.util.Map;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

/**
 * Port of tests/integration/labs.test.js ("department labs"). The /api/venues routes belong to the venue feature (no
 * controller in this copy yet), so the first two tests drive {@link VenueService} directly; the third — events booking
 * labs on create and edit — goes through the events API.
 */
class LabsIntegrationTest extends IntegrationTest {

    @Autowired
    VenueService venueService;

    TestData.Venues venues;
    EventFlows flows;
    TestUser admin;
    TestUser president;
    TestUser allPresident;
    Club club;
    Club openClub;

    @BeforeEach
    void seed() {
        venues = data.seedReferenceData();
        flows = new EventFlows(api, mongo);
        admin = data.admin();
        TestUser mentor = data.faculty();
        president = data.student(spec -> spec.name("CE President"));
        allPresident = data.student(spec -> spec.name("Open President"));
        club = data.activeClub("Coding Club", mentor, president);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(club.getId()))), new Update().set("departmentCodes", List.of("CE", "IT")), Club.class);
        openClub = data.activeClub("Music Club", mentor, allPresident);
        mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(openClub.getId()))), new Update().set("allDepartments", true).set("departmentCodes", List.of()),
                Club.class);
    }

    AuthUser actor(TestUser user) {
        return AuthUser.of(mongo.findById(user.id(), User.class));
    }

    Venue lab(String name, String location, int capacity, String department) {
        return venueService.createVenue(actor(admin), map("name", name, "location", location, "capacity", capacity, "type", "LAB", "departmentCodes", List.of(department)));
    }

    List<String> availableNames(String clubId, String departments) {
        return venueService.getAvailableVenues(TestData.futureDate(10), null, "10:00", "12:00", null, clubId, departments).stream()
                .map(venue -> String.valueOf(venue.get("name"))).toList();
    }

    @Test
    void theAdminAddsLabsThatBelongToDepartments() {
        assertThatThrownBy(() -> venueService.createVenue(actor(admin), map("name", "Robotics Lab", "location", "ME Block", "capacity", 30, "type", "LAB")))
                .isInstanceOf(AppException.class).hasMessageContaining("department");
        assertThatThrownBy(() -> venueService.createVenue(actor(admin), map("name", "X Lab", "location", "Nowhere", "capacity", 30, "type", "LAB", "departmentCodes", List.of("ZZ"))))
                .hasMessageContaining("Unknown department: ZZ");
        assertThatThrownBy(() -> venueService.createVenue(actor(president), map("name", "Sneaky Lab", "location", "CE", "capacity", 30, "type", "LAB", "departmentCodes", List.of("CE"))))
                .isInstanceOf(AppException.class).satisfies(error -> assertThat(((AppException) error).getStatus()).isEqualTo(403));

        Venue ceLab = venueService.createVenue(actor(admin), map("name", "CE Software Lab", "location", "CE Block", "capacity", 60, "type", "LAB", "departmentCodes", List.of("ce")));
        assertThat(ceLab.getType().name()).isEqualTo("LAB");
        assertThat(ceLab.getDepartmentCodes()).containsExactly("CE");

        // A hall never keeps departments.
        Venue hall = venueService.updateVenue(actor(admin), venues.hall().getId(), map("type", "HALL", "departmentCodes", List.of("CE")));
        assertThat(hall.getType().name()).isEqualTo("HALL");
        assertThat(hall.getDepartmentCodes()).isEmpty();
    }

    @Test
    void availabilityListsOnlyTheLabsOfTheEventsDepartments() {
        lab("CE Software Lab", "CE Block", 60, "CE");
        lab("IT Networks Lab", "IT Block", 50, "IT");
        lab("ME Workshop", "ME Block", 40, "ME");

        // Coding Club (CE, IT): CE and IT labs, never the ME workshop; halls are always there.
        assertThat(availableNames(club.getId(), null)).contains("CE Software Lab", "IT Networks Lab", "Auditorium").doesNotContain("ME Workshop");
        // An event for IT students only: only the IT lab.
        assertThat(availableNames(club.getId(), "IT")).contains("IT Networks Lab").doesNotContain("CE Software Lab");
        // An all-department club: every lab — unless the event names its departments.
        assertThat(availableNames(openClub.getId(), null)).contains("CE Software Lab", "IT Networks Lab", "ME Workshop");
        assertThat(availableNames(openClub.getId(), "ME")).doesNotContain("CE Software Lab");
        // The plain venue list filters the same way.
        assertThat(venueService.listVenues("ACTIVE", null, club.getId(), null).stream().map(Venue::getName)).doesNotContain("ME Workshop");
    }

    @Test
    void eventsCantBookAnotherDepartmentsLabOnCreateOrOnEdit() {
        Venue ceLab = lab("CE Software Lab", "CE Block", 60, "CE");
        Venue itLab = lab("IT Networks Lab", "IT Block", 50, "IT");
        Venue meLab = lab("ME Workshop", "ME Block", 40, "ME");

        Response wrong = flows.create(president, club, meLab);
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.message()).contains("ME Workshop is a ME lab");

        Response itEvent = flows.create(president, club, itLab, "title", "Networking 101", "eligibility", map("departments", List.of("IT")));
        assertThat(itEvent.status()).isEqualTo(201);
        String id = itEvent.string("$.data._id");
        // Narrowing the audience to CE makes the IT lab wrong.
        Response narrowed = api.as(president).put("/api/events/" + id, map("eligibility", map("departments", List.of("CE"))));
        assertThat(narrowed.status()).isEqualTo(400);
        assertThat(narrowed.message()).contains("IT Networks Lab");
        assertThat(api.as(president).put("/api/events/" + id, map("venue", ceLab.getId(), "eligibility", map("departments", List.of("CE")))).status()).isEqualTo(200);

        // An all-department club's open event may use any lab.
        Map<String, Object> jam = EventFlows.payload(openClub, meLab, "title", "Jam session", "maxParticipants", 30);
        assertThat(api.as(allPresident).post("/api/events", jam).status()).isEqualTo(201);
    }
}
