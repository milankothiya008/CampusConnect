package com.campusconnect.domain.venue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class VenueDocumentTest extends MongoDocumentTest {

    @Autowired
    VenueRepository venues;

    private static Venue venue(String name) {
        Venue venue = new Venue();
        venue.setName(name);
        venue.setLocation("Block A");
        venue.setCapacity(60);
        return venue;
    }

    @Test
    void venueRoundTripHidesTheBookingLock() {
        Venue lab = venue("Lab 3");
        lab.setType(VenueType.LAB);
        lab.setDepartmentCodes(List.of("CE", "IT"));
        lab.setStatus(VenueStatus.INACTIVE);
        lab.setBookingLock("lock-1");
        lab.setBookingLockExpires(at(1));
        venues.save(lab);

        assertSameDocument(venues.findById(lab.getId()).orElseThrow(), lab);
        Document raw = raw("venues");
        assertThat(raw.getString("type")).isEqualTo("LAB");
        assertThat(raw.getString("bookingLock")).isEqualTo("lock-1");
        JsonNode json = toJson(lab);
        assertThat(json.get("_id").stringValue()).isEqualTo(lab.getId());
        assertThat(json.propertyNames()).doesNotContain("bookingLock", "bookingLockExpires", "id");
        assertThat(json.get("departmentCodes").size()).isEqualTo(2);
        assertThat(json.get("capacity").intValue()).isEqualTo(60);
    }

    @Test
    void venueNamesAreUnique() {
        Venue hall = venues.save(venue("Main Hall"));

        assertThat(hall.getType()).isEqualTo(VenueType.HALL);
        assertThat(raw("venues").getString("status")).isEqualTo("ACTIVE");
        assertThat(venues.existsByName("Main Hall")).isTrue();
        assertThatThrownBy(() -> venues.save(venue("Main Hall"))).isInstanceOf(DuplicateKeyException.class);
    }
}
