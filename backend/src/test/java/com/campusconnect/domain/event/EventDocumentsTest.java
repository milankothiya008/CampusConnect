package com.campusconnect.domain.event;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.common.FormQuestion;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class EventDocumentsTest extends MongoDocumentTest {

    @Autowired
    EventRepository events;

    @Autowired
    EventRegistrationRepository registrations;

    @Autowired
    TeamRepository teams;

    @Autowired
    EventFeedbackRepository feedback;

    @Autowired
    EventMediaRepository gallery;

    // ------------------------------------------------------------------ Event

    static Event fullEvent() {
        Event event = new Event();
        event.setTitle("HackNight");
        event.setShortDescription("A night of building");
        event.setDescription("Build anything in 12 hours.");
        event.setCategory("HACKATHON");
        event.setPoster("https://res.cloudinary.com/demo/image/upload/v1/poster.jpg");
        event.setClub(newId());
        event.setVenue(newId());
        event.setEventDate(at(0));
        event.setEndDate(at(1440));
        event.setStartTime("10:00");
        event.setEndTime("22:00");
        event.setStartAt(at(0));
        event.setEndAt(at(720));
        event.setRegistrationStart(at(-10_000));
        event.setRegistrationEnd(at(-60));
        event.setRegistrationClosed(true);
        event.setMaxParticipants(40);
        event.setRegisteredCount(12);
        event.setWaitlistCount(3);
        event.setParticipationMode(ParticipationMode.TEAM);
        event.setMinTeamSize(2);
        event.setMaxTeamSize(4);
        event.getEligibility().setDepartments(List.of("CE", "IT"));
        event.getEligibility().setBatches(List.of("23", "24"));
        event.getEligibility().setNotes("Second and third years");
        event.setRules("Be kind.");

        FormQuestion question = new FormQuestion();
        question.setType("SINGLE_CHOICE");
        question.setLabel("T-shirt size");
        question.setHelp("For the team kit");
        question.setRequired(true);
        question.setOptions(List.of("S", "M", "L"));
        question.setScope("TEAM");
        event.getRegistrationForm().setEnabled(true);
        event.getRegistrationForm().setQuestions(List.of(question));

        BudgetItem pizza = new BudgetItem();
        pizza.setItem("Pizza");
        pizza.setQuantity(10);
        pizza.setUnitCost(249.5);
        pizza.setNote("Dinner");
        event.setBudgetItems(List.of(pizza));
        EquipmentItem projector = new EquipmentItem();
        projector.setName("Projector");
        projector.setQuantity(2);
        projector.setNote("With HDMI");
        event.setEquipment(List.of(projector));
        event.setBudgetNote("A sponsor pays for drinks");

        Expense spent = new Expense();
        spent.setItem("Pizza");
        spent.setAmount(2310.75);
        spent.setNote("Receipt 12");
        event.getExpenses().setItems(List.of(spent));
        event.getExpenses().setNote("Under budget");
        event.getExpenses().setSubmittedAt(at(800));
        event.getExpenses().setSubmittedBy(newId());

        event.setLikeCount(7);
        event.setCertificatesEnabled(true);
        SentReminder reminder = new SentReminder();
        reminder.setKind("MANUAL_UPDATE");
        reminder.setAt(at(-30));
        reminder.setBy(newId());
        reminder.setRecipients(12);
        event.setRemindersSent(List.of(reminder));
        event.getContact().setName("Asha");
        event.getContact().setEmail("asha@uni.edu");
        event.getContact().setPhone("+919876543210");
        event.setOrganizer(newId());
        event.setCreatedBy(newId());
        event.setUpdatedBy(newId());
        event.setStatus(EventStatus.PUBLISHED);
        event.setReviewComment("Looks good");
        event.setReviewedBy(newId());
        event.setReviewedAt(at(-5000));
        event.setSubmittedAt(at(-6000));
        event.setPublishedAt(at(-4000));
        event.setCompletedAt(at(721));
        event.setCancelledAt(at(722));
        event.setCancellationReason("Not cancelled, just filled in");
        event.getCheckIn().setStatus(CheckInStatus.CLOSED);
        event.getCheckIn().setOpenedAt(at(-10));
        event.getCheckIn().setOpenedBy(newId());
        event.getCheckIn().setClosedAt(at(721));
        event.getCheckIn().setClosedBy(newId());

        EventRevision revision = new EventRevision();
        revision.setStatus(RevisionStatus.APPROVED);
        revision.getChanges().put("title", "HackNight 2");
        revision.getChanges().put("maxParticipants", 50);
        revision.getChanges().put("tags", List.of("ai", "web"));
        revision.getChanges().put("contact", Map.of("name", "Bina"));
        revision.getChanges().put("startAt", at(60));
        revision.setFields(List.of("title", "maxParticipants", "startAt"));
        revision.setNote("Bigger room");
        revision.setRequestedBy(newId());
        revision.setRequestedAt(at(-100));
        revision.setReviewComment("Fine");
        revision.setReviewedBy(newId());
        revision.setReviewedAt(at(-50));
        event.setRevision(revision);
        return event;
    }

    @Test
    void eventRoundTrip() {
        Event saved = events.save(fullEvent());

        Event reloaded = events.findById(saved.getId()).orElseThrow();

        assertThat(reloaded.getCreatedAt()).isNotNull();
        assertThat(reloaded.getUpdatedAt()).isNotNull();
        assertSameDocument(reloaded, saved, "revision.changes");
        // Mixed values come back as BSON types, not as the Java types that were written.
        Map<String, Object> changes = reloaded.getRevision().getChanges();
        assertThat(changes).containsEntry("title", "HackNight 2").containsEntry("maxParticipants", 50);
        assertThat(changes.get("tags")).isEqualTo(List.of("ai", "web"));
        assertThat(changes.get("contact")).isInstanceOf(Map.class).isEqualTo(Map.of("name", "Bina"));
        assertThat(changes.get("startAt")).isInstanceOf(Date.class);
        assertThat(((Date) changes.get("startAt")).toInstant()).isEqualTo(at(60));
    }

    @Test
    void eventIsStoredWithObjectIdsAndDates() {
        Event event = events.save(fullEvent());

        Document raw = raw("events");

        assertObjectIds(raw, "_id", "club", "venue", "organizer", "createdBy", "updatedBy", "reviewedBy",
                "expenses.submittedBy", "remindersSent.0.by", "checkIn.openedBy", "checkIn.closedBy",
                "revision.requestedBy", "revision.reviewedBy",
                // sub-documents with their own _id
                "budgetItems.0._id", "equipment.0._id", "expenses.items.0._id", "registrationForm.questions.0._id");
        assertThat(valueAt(raw, "budgetItems.0._id").toString()).isEqualTo(event.getBudgetItems().get(0).getId());
        assertAbsent(raw, "remindersSent.0._id", "eligibility._id", "contact._id", "checkIn._id", "expenses._id",
                "registrationForm._id", "revision._id", "id", "budgetItems.0.id");
        assertDates(raw, "eventDate", "endDate", "startAt", "registrationEnd", "createdAt", "updatedAt",
                "remindersSent.0.at", "revision.requestedAt", "checkIn.openedAt");
        assertThat(raw.getString("status")).isEqualTo("PUBLISHED");
        assertThat(raw.getString("participationMode")).isEqualTo("TEAM");
        assertThat(valueAt(raw, "checkIn.status")).isEqualTo("CLOSED");
        assertThat(valueAt(raw, "revision.status")).isEqualTo("APPROVED");
        assertThat(valueAt(raw, "budgetItems.0.unitCost")).isEqualTo(249.5);
        assertThat(valueAt(raw, "registrationForm.questions.0.scope")).isEqualTo("TEAM");
    }

    @Test
    void newEventHasTheMongooseDefaults() {
        Event event = new Event();
        event.setTitle("Talk");
        event.setClub(newId());
        event.setVenue(newId());
        event.setCreatedBy(newId());
        events.save(event);

        Document raw = raw("events");
        // Null fields are not written (like a missing path, which queries treat as null).
        assertAbsent(raw, "revision", "endDate", "poster", "maxParticipants", "organizer", "updatedBy",
                "reviewComment", "checkIn.openedAt", "expenses.submittedBy");
        assertThat(raw.getString("status")).isEqualTo("DRAFT");
        assertThat(raw.getList("budgetItems", Object.class)).isEmpty();
        assertThat(valueAt(raw, "checkIn.status")).isEqualTo("NOT_STARTED");
        assertThat(valueAt(raw, "registrationForm.enabled")).isEqualTo(false);
        assertThat(valueAt(raw, "contact.email")).isEqualTo("");
        assertThat(raw.getInteger("minTeamSize")).isEqualTo(1);

        JsonNode json = toJson(events.findById(event.getId()).orElseThrow());
        assertThat(json.get("revision").isNull()).isTrue(); // nulls are included in the API JSON
        assertThat(json.get("endDate").isNull()).isTrue();
        assertThat(json.get("participationMode").stringValue()).isEqualTo("INDIVIDUAL");
        assertThat(json.get("eligibility").get("departments").isArray()).isTrue();
        assertThat(json.get("checkIn").get("status").stringValue()).isEqualTo("NOT_STARTED");
    }

    @Test
    void eventJson() {
        Event event = events.save(fullEvent());

        JsonNode json = toJson(event);

        assertThat(json.get("_id").stringValue()).isEqualTo(event.getId());
        assertThat(json.has("id")).isFalse();
        assertThat(json.get("club").stringValue()).isEqualTo(event.getClub());
        assertThat(json.get("startAt").stringValue()).isEqualTo("2026-11-02T04:30:00.123Z");
        assertThat(json.get("createdAt").isString()).isTrue();
        JsonNode budgetLine = json.get("budgetItems").get(0);
        assertThat(budgetLine.get("_id").stringValue()).isEqualTo(event.getBudgetItems().get(0).getId());
        assertThat(budgetLine.has("id")).isFalse();
        assertThat(budgetLine.get("unitCost").doubleValue()).isEqualTo(249.5);
        assertThat(json.get("registrationForm").get("questions").get(0).get("scope").stringValue()).isEqualTo("TEAM");
        assertThat(json.get("revision").get("changes").get("title").stringValue()).isEqualTo("HackNight 2");
        assertThat(json.get("checkIn").get("status").stringValue()).isEqualTo("CLOSED");
        assertThat(json.get("remindersSent").get(0).has("_id")).isFalse();
        assertThat(json.get("certificatesEnabled").booleanValue()).isTrue();
    }

    @Test
    void readsAnEventAsTheNodeAppStoredIt() {
        ObjectId id = new ObjectId();
        ObjectId club = new ObjectId();
        ObjectId budgetLine = new ObjectId();
        ObjectId proposedVenue = new ObjectId();
        // Mongoose writes explicit nulls and __v, no _class, and integral numbers as int32.
        Document stored = new Document("_id", id)
                .append("title", "Talk")
                .append("club", club)
                .append("venue", new ObjectId())
                .append("startAt", Date.from(at(0)))
                .append("endAt", Date.from(at(60)))
                .append("endDate", null)
                .append("maxParticipants", null)
                .append("registeredCount", 3)
                .append("budgetItems", List.of(new Document("_id", budgetLine)
                        .append("item", "Tea").append("quantity", 2).append("unitCost", 15).append("note", "")))
                .append("checkIn", new Document("status", "OPEN").append("openedAt", Date.from(at(-1)))
                        .append("openedBy", new ObjectId()).append("closedAt", null).append("closedBy", null))
                .append("revision", new Document("status", "PENDING_APPROVAL")
                        .append("changes", new Document("venue", proposedVenue).append("startAt", Date.from(at(30))))
                        .append("fields", List.of("venue", "startAt"))
                        .append("note", "")
                        .append("requestedBy", new ObjectId())
                        .append("requestedAt", Date.from(at(-5)))
                        .append("reviewComment", null)
                        .append("reviewedBy", null)
                        .append("reviewedAt", null))
                .append("status", "PUBLISHED")
                .append("createdAt", Date.from(at(-100)))
                .append("updatedAt", Date.from(at(-50)))
                .append("__v", 0);
        mongo.getCollection("events").insertOne(stored);

        Event event = events.findById(id.toHexString()).orElseThrow();

        assertThat(event.getClub()).isEqualTo(club.toHexString());
        assertThat(event.getBudgetItems().get(0).getId()).isEqualTo(budgetLine.toHexString());
        assertThat(event.getBudgetItems().get(0).getUnitCost()).isEqualTo(15.0);
        assertThat(event.getMaxParticipants()).isNull();
        assertThat(event.getCreatedAt()).isEqualTo(at(-100));
        assertThat(event.getCheckIn().getStatus()).isEqualTo(CheckInStatus.OPEN);
        assertThat(event.getRevision().getStatus()).isEqualTo(RevisionStatus.PENDING_APPROVAL);
        // Paths the stored document lacks keep their defaults.
        assertThat(event.getParticipationMode()).isEqualTo(ParticipationMode.INDIVIDUAL);
        assertThat(event.getEligibility().getDepartments()).isEmpty();
        assertThat(event.getContact().getEmail()).isEmpty();
        // Mixed values keep their BSON types; the app's JSON writes ObjectIds as hex strings.
        assertThat(event.getRevision().getChanges().get("venue")).isEqualTo(proposedVenue);
        JsonNode json = toJson(event);
        assertThat(json.get("_id").stringValue()).isEqualTo(id.toHexString());
        assertThat(json.get("revision").get("changes").get("venue").stringValue())
                .isEqualTo(proposedVenue.toHexString());
        assertThat(json.get("checkIn").get("openedBy").stringValue())
                .isEqualTo(event.getCheckIn().getOpenedBy());
    }

    @Test
    void readsGalleryNumbersStoredAsInt32() {
        ObjectId id = new ObjectId();
        mongo.getCollection("eventmedias").insertOne(new Document("_id", id)
                .append("event", new ObjectId())
                .append("club", new ObjectId())
                .append("uploader", new ObjectId())
                .append("uploaderRole", "MEMBER")
                .append("media", new Document("kind", "VIDEO").append("provider", "cloudinary").append("key", "k")
                        .append("version", 1_712_345_678).append("format", "mp4").append("width", 1920)
                        .append("height", 1080).append("duration", 12).append("bytes", 4_194_304))
                .append("status", "PENDING")
                .append("likeCount", 0)
                .append("__v", 0));

        MediaFile media = gallery.findById(id.toHexString()).orElseThrow().getMedia();

        assertThat(media.getVersion()).isEqualTo(1_712_345_678L);
        assertThat(media.getDuration()).isEqualTo(12.0);
        assertThat(media.getBytes()).isEqualTo(4_194_304L);
        assertThat(media.getKind()).isEqualTo(MediaKind.VIDEO);
    }

    // ------------------------------------------------------------------ EventRegistration

    private static EventRegistration registration(String event, String user, RegistrationStatus status, String ticket) {
        EventRegistration registration = new EventRegistration();
        registration.setEvent(event);
        registration.setUser(user);
        registration.setStatus(status);
        registration.setTicketCode(ticket);
        return registration;
    }

    @Test
    void registrationRoundTrip() {
        EventRegistration registration = registration(newId(), newId(), RegistrationStatus.REGISTERED, "QX7K2P");
        registration.setRegisteredAt(at(-100));
        registration.setWaitlistedAt(at(-90));
        registration.setPromotedAt(at(-80));
        registration.setTeam(newId());
        registration.setTeamRole(TeamRole.LEADER);
        registration.setAnswers(List.of(
                new FormAnswer(newId(), "Vegetarian", List.of()), new FormAnswer(newId(), "", List.of("M"))));
        registration.setTicketIssuedAt(at(-80));
        registration.setCheckedInAt(at(5));
        registration.setCheckedInBy(newId());
        registration.setCheckInMethod(CheckInMethod.QR);
        registration.setCheckInNote("On time");
        registrations.save(registration);

        assertSameDocument(registrations.findById(registration.getId()).orElseThrow(), registration);
        Document raw = raw("eventregistrations");
        assertObjectIds(raw, "_id", "event", "user", "team", "checkedInBy", "answers.0.question", "answers.1.question");
        assertAbsent(raw, "answers.0._id");
        assertThat(raw.getString("ticketCode")).isEqualTo("QX7K2P");
        assertThat(raw.getString("teamRole")).isEqualTo("LEADER");
        assertThat(raw.getString("checkInMethod")).isEqualTo("QR");

        JsonNode json = toJson(registration);
        assertThat(json.get("_id").stringValue()).isEqualTo(registration.getId());
        assertThat(json.get("answers").get(1).get("choices").get(0).stringValue()).isEqualTo("M");
        assertThat(json.get("answers").get(0).get("question").stringValue())
                .isEqualTo(registration.getAnswers().get(0).getQuestion());
        assertThat(registrations.findByEventAndUser(registration.getEvent(), registration.getUser())).isPresent();
        assertThat(registrations.findByTicketCode("QX7K2P")).isPresent();
    }

    @Test
    void waitlistedRegistrationsLeaveTicketCodeUnsetAndDoNotCollide() {
        String event = newId();
        registrations.save(registration(event, newId(), RegistrationStatus.WAITLISTED, null));
        registrations.save(registration(event, newId(), RegistrationStatus.WAITLISTED, null));

        assertThat(registrations.count()).isEqualTo(2);
        for (Document raw : mongo.getCollection("eventregistrations").find()) {
            assertAbsent(raw, "ticketCode", "teamRole", "checkInMethod", "team");
        }
        JsonNode json = toJson(registrations.findAll().get(0));
        assertThat(json.get("ticketCode").isNull()).isTrue();
    }

    @Test
    void clearingTheTicketCodeRemovesTheField() {
        EventRegistration registration =
                registrations.save(registration(newId(), newId(), RegistrationStatus.REGISTERED, "AB12CD"));

        registration.setStatus(RegistrationStatus.WAITLISTED);
        registration.setTicketCode(null);
        registrations.save(registration); // save() replaces the whole document

        assertAbsent(raw("eventregistrations"), "ticketCode");
        registrations.save(registration(registration.getEvent(), newId(), RegistrationStatus.WAITLISTED, null));
    }

    @Test
    void ticketCodesAreUnique() {
        String event = newId();
        registrations.save(registration(event, newId(), RegistrationStatus.REGISTERED, "AB12CD"));

        assertThatThrownBy(() -> registrations.save(
                        registration(newId(), newId(), RegistrationStatus.REGISTERED, "AB12CD")))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void aStudentRegistersOncePerEvent() {
        String event = newId();
        String user = newId();
        registrations.save(registration(event, user, RegistrationStatus.REGISTERED, "AAA111"));

        assertThatThrownBy(() -> registrations.save(registration(event, user, RegistrationStatus.WAITLISTED, null)))
                .isInstanceOf(DuplicateKeyException.class);
        registrations.save(registration(newId(), user, RegistrationStatus.REGISTERED, "BBB222"));
    }

    // ------------------------------------------------------------------ Team

    private static Team team(String event, String name, TeamStatus status) {
        Team team = new Team();
        team.setEvent(event);
        team.setName(name);
        team.setNameKey(name.toLowerCase());
        team.setLeader(newId());
        team.setStatus(status);
        return team;
    }

    @Test
    void teamRoundTrip() {
        Team team = team(newId(), "Byte Me", TeamStatus.ACTIVE);
        TeamMember leader = new TeamMember();
        leader.setUser(team.getLeader());
        leader.setStatus(TeamMemberStatus.LEADER);
        leader.setInvitedAt(at(-10));
        leader.setRespondedAt(at(-10));
        TeamMember invited = new TeamMember();
        invited.setUser(newId());
        invited.setStatus(TeamMemberStatus.INVITED);
        invited.setInvitedAt(at(-5));
        team.setMembers(List.of(leader, invited));
        team.setSize(2);
        team.setAnswers(List.of(new FormAnswer(newId(), "Team Rocket", List.of())));
        team.setDisbandedAt(at(100));
        teams.save(team);

        assertSameDocument(teams.findById(team.getId()).orElseThrow(), team);
        Document raw = raw("teams");
        assertObjectIds(raw, "_id", "event", "leader", "members.0.user", "members.1.user", "answers.0.question");
        assertAbsent(raw, "members.0._id", "members.1.respondedAt");
        assertThat(valueAt(raw, "members.1.status")).isEqualTo("INVITED");
        JsonNode json = toJson(team);
        assertThat(json.get("members").get(0).get("status").stringValue()).isEqualTo("LEADER");
        assertThat(json.get("members").get(1).get("respondedAt").isNull()).isTrue();
    }

    @Test
    void activeTeamNamesAreUniquePerEventIgnoringCase() {
        String event = newId();
        teams.save(team(event, "Byte Me", TeamStatus.DISBANDED));
        teams.save(team(event, "Byte Me", TeamStatus.ACTIVE)); // a disbanded team frees its name

        assertThatThrownBy(() -> teams.save(team(event, "BYTE ME", TeamStatus.ACTIVE)))
                .isInstanceOf(DuplicateKeyException.class);
        teams.save(team(newId(), "Byte Me", TeamStatus.ACTIVE)); // another event
        teams.save(team(event, "Byte Me", TeamStatus.DISBANDED)); // inactive duplicates are fine
    }

    // ------------------------------------------------------------------ EventFeedback, EventMedia

    @Test
    void feedbackRoundTripAndOnePerParticipant() {
        EventFeedback note = new EventFeedback();
        note.setEvent(newId());
        note.setClub(newId());
        note.setUser(newId());
        note.setRating(4);
        note.setNote("Great mentors");
        feedback.save(note);

        assertSameDocument(feedback.findById(note.getId()).orElseThrow(), note);
        assertObjectIds(raw("eventfeedbacks"), "_id", "event", "club", "user");
        assertThat(feedback.findByEventAndUser(note.getEvent(), note.getUser())).isPresent();
        EventFeedback again = new EventFeedback();
        again.setEvent(note.getEvent());
        again.setClub(note.getClub());
        again.setUser(note.getUser());
        again.setRating(1);
        assertThatThrownBy(() -> feedback.save(again)).isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void galleryMediaRoundTrip() {
        EventMedia item = new EventMedia();
        item.setEvent(newId());
        item.setClub(newId());
        item.setUploader(newId());
        item.setUploaderRole(UploaderRole.PARTICIPANT);
        MediaFile media = new MediaFile();
        media.setKind(MediaKind.VIDEO);
        media.setProvider("cloudinary");
        media.setKey("cc/gallery/abc123");
        media.setVersion(1_712_345_678L);
        media.setFormat("mp4");
        media.setWidth(1920);
        media.setHeight(1080);
        media.setDuration(12.5);
        media.setBytes(4_194_304L);
        item.setMedia(media);
        item.setStatus(GalleryStatus.APPROVED);
        item.setLikeCount(3);
        item.setReviewedBy(newId());
        item.setReviewedAt(at(30));
        gallery.save(item);

        assertSameDocument(gallery.findById(item.getId()).orElseThrow(), item);
        Document raw = raw("eventmedias");
        assertObjectIds(raw, "_id", "event", "club", "uploader", "reviewedBy");
        assertAbsent(raw, "media._id");
        assertThat(valueAt(raw, "media.kind")).isEqualTo("VIDEO");
        assertThat(raw.getString("uploaderRole")).isEqualTo("PARTICIPANT");
        JsonNode json = toJson(item);
        assertThat(json.get("media").get("provider").stringValue()).isEqualTo("cloudinary");
        assertThat(json.get("media").get("duration").doubleValue()).isEqualTo(12.5);
        assertThat(json.get("status").stringValue()).isEqualTo("APPROVED");
    }
}
