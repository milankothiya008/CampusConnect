package com.campusconnect.domain.venue;

import com.campusconnect.domain.common.BaseDocument;
import com.fasterxml.jackson.annotation.JsonIgnore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** A bookable venue (models/Venue.js). Labs are limited to their departments' events. */
@Getter
@Setter
@NoArgsConstructor
@Document("venues")
public class Venue extends BaseDocument {

    @Indexed(name = "name_1", unique = true)
    private String name;

    private String location;
    private int capacity;
    private VenueType type = VenueType.HALL;

    /** Labs only: the departments whose events may book it. */
    private List<String> departmentCodes = new ArrayList<>();

    private VenueStatus status = VenueStatus.ACTIVE;

    /** Short-lived booking lock (VenueService); kept for compatibility, the Java service locks in Redis. */
    @JsonIgnore
    private String bookingLock;

    @JsonIgnore
    private Instant bookingLockExpires;
}
