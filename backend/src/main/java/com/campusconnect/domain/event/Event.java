package com.campusconnect.domain.event;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A club event (models/Event.js): drafted by the club, approved by the faculty mentor, then published for
 * registration. Any write must clear the cached events feed (utils/Caches.js eventFeedCache).
 *
 * <p>Checked by the service before saving (Mongoose {@code pre("validate")}):
 * <ul>
 *   <li>{@code endAt} after {@code startAt}: "Event end must be after event start";</li>
 *   <li>{@code minTeamSize <= maxTeamSize}: "Minimum team size cannot be larger than the maximum";</li>
 *   <li>{@code registrationEnd} after {@code registrationStart}: "Registration deadline must be after registration
 *       start".</li>
 * </ul>
 */
@Getter
@Setter
@NoArgsConstructor
@Document("events")
@CompoundIndexes({
    @CompoundIndex(name = "club_1_status_1", def = "{'club': 1, 'status': 1}"),
    @CompoundIndex(name = "status_1_startAt_1", def = "{'status': 1, 'startAt': 1}"),
    @CompoundIndex(name = "venue_1_startAt_1_endAt_1", def = "{'venue': 1, 'startAt': 1, 'endAt': 1}"),
    @CompoundIndex(
            name = "registrationStart_1_registrationEnd_1",
            def = "{'registrationStart': 1, 'registrationEnd': 1}"),
    @CompoundIndex(name = "club_1_revision.status_1", def = "{'club': 1, 'revision.status': 1}")
})
public class Event extends BaseDocument {

    private String title;
    private String shortDescription;
    private String description;

    /** One of core.util.Categories.EVENT_CATEGORIES (HACKATHON events get the hackathon tools). */
    private String category;

    private String poster;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String venue;

    private Instant eventDate;

    /** Multi-day and overnight events end on a later day; null means the event ends on eventDate. */
    private Instant endDate;

    /** "HH:mm", university time. */
    private String startTime;

    private String endTime;
    private Instant startAt;
    private Instant endAt;
    private Instant registrationStart;
    private Instant registrationEnd;
    private boolean registrationClosed = false;

    /** Null = unlimited. For team events this is the number of teams. */
    private Integer maxParticipants;

    private int registeredCount = 0;

    /** Students queued for a seat once the event is full (see WaitlistService). */
    private int waitlistCount = 0;

    /**
     * Individual events take one student per registration; team events take a team led by the student who
     * registers (see TeamService).
     */
    private ParticipationMode participationMode = ParticipationMode.INDIVIDUAL;

    private int minTeamSize = 1;
    private int maxTeamSize = 1;
    private EventEligibility eligibility = new EventEligibility();
    private String rules = "";
    private RegistrationForm registrationForm = new RegistrationForm();

    // Budget and equipment the club asks for; the faculty mentor approves them with the event.
    private List<BudgetItem> budgetItems = new ArrayList<>();
    private List<EquipmentItem> equipment = new ArrayList<>();
    private String budgetNote = "";

    /** What was actually spent, recorded by the club once the event has started. */
    private EventExpenses expenses = new EventExpenses();

    private int likeCount = 0;

    /**
     * Switched on per event by the president: participation certificates for checked-in students, merit
     * certificates for winners in the published results (see CertificateService).
     */
    private boolean certificatesEnabled = false;

    private List<SentReminder> remindersSent = new ArrayList<>();
    private EventContact contact = new EventContact();

    @Field(targetType = FieldType.OBJECT_ID)
    private String organizer;

    @Field(targetType = FieldType.OBJECT_ID)
    private String createdBy;

    @Field(targetType = FieldType.OBJECT_ID)
    private String updatedBy;

    private EventStatus status = EventStatus.DRAFT;
    private String reviewComment;

    @Field(targetType = FieldType.OBJECT_ID)
    private String reviewedBy;

    private Instant reviewedAt;
    private Instant submittedAt;
    private Instant publishedAt;
    private Instant completedAt;
    private Instant cancelledAt;
    private String cancellationReason;
    private EventCheckIn checkIn = new EventCheckIn();

    /** Proposed changes to a published event, waiting for (or approved by) the mentor; null when there are none. */
    private EventRevision revision;
}
