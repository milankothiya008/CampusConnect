package com.campusconnect.domain.event;

import com.campusconnect.domain.common.BaseDocument;
import com.campusconnect.domain.common.FormAnswer;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A student's registration for an event (models/EventRegistration.js), with their ticket and attendance. */
@Getter
@Setter
@NoArgsConstructor
@Document("eventregistrations")
@CompoundIndexes({
    @CompoundIndex(name = "event_1_user_1", def = "{'event': 1, 'user': 1}", unique = true),
    @CompoundIndex(name = "user_1_status_1", def = "{'user': 1, 'status': 1}"),
    @CompoundIndex(name = "event_1_status_1", def = "{'event': 1, 'status': 1}"),
    @CompoundIndex(name = "event_1_status_1_waitlistedAt_1", def = "{'event': 1, 'status': 1, 'waitlistedAt': 1}"),
    @CompoundIndex(name = "team_1_status_1", def = "{'team': 1, 'status': 1}"),
    @CompoundIndex(name = "event_1_checkedInAt_1", def = "{'event': 1, 'checkedInAt': 1}")
})
public class EventRegistration extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private RegistrationStatus status = RegistrationStatus.REGISTERED;
    private Instant registeredAt = Instant.now();

    /** Queue order for WAITLISTED registrations (first in, first promoted). */
    private Instant waitlistedAt;

    /** Set when a waitlisted student was moved into a freed seat. */
    private Instant promotedAt;

    /**
     * Team events: the team this registration belongs to. The leader's registration holds the team's place (and
     * its spot in the waitlist); members' registrations follow the leader's status.
     */
    @Field(targetType = FieldType.OBJECT_ID)
    private String team;

    private TeamRole teamRole;

    /** Answers to the event's registration form (the questions every member answers). */
    private List<FormAnswer> answers = new ArrayList<>();

    /**
     * The ticket: a short upper-case code shown on the QR ticket (see TicketToken). Issued whenever the registration
     * gets a place, and reissued on re-registration so an old QR stops working. Left unset (null is not written)
     * while waitlisted, so the partial unique index ignores it.
     */
    @Indexed(name = "ticketCode_1", unique = true, partialFilter = "{'ticketCode': {'$type': 'string'}}")
    private String ticketCode;

    private Instant ticketIssuedAt;

    // Attendance, recorded at the door by a club officer.
    private Instant checkedInAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String checkedInBy;

    private CheckInMethod checkInMethod;
    private String checkInNote;
}
