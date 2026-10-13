package com.campusconnect.domain.recruitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.data.mongodb.core.query.Criteria.where;
import static org.springframework.data.mongodb.core.query.Query.query;

import com.campusconnect.domain.MongoDocumentTest;
import com.campusconnect.domain.common.MediaKind;
import java.time.Instant;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.query.Update;
import tools.jackson.databind.JsonNode;

class RecruitmentDocumentsTest extends MongoDocumentTest {

    @Autowired
    RecruitmentDriveRepository drives;

    @Autowired
    RecruitmentApplicationRepository applications;

    // ------------------------------------------------------------------ RecruitmentDrive

    private static RecruitmentDrive fullDrive() {
        RecruitmentQuestion why = new RecruitmentQuestion();
        why.setType(QuestionType.PARAGRAPH);
        why.setLabel("Why do you want to join?");
        why.setHelp("A few lines");
        why.setRequired(true);
        RecruitmentQuestion resume = new RecruitmentQuestion();
        resume.setType(QuestionType.FILE);
        resume.setLabel("Resume");
        RecruitmentQuestion team = new RecruitmentQuestion();
        team.setType(QuestionType.SINGLE_CHOICE);
        team.setLabel("Team");
        team.setOptions(List.of("Web", "Design"));
        RecruitmentPage page = new RecruitmentPage();
        page.setTitle("About you");
        page.setDescription("Basics first");
        page.setQuestions(List.of(why, resume, team));

        RecruitmentRound screening = new RecruitmentRound();
        screening.setName("Screening");
        screening.setMode(RoundMode.SCREENING);
        screening.setStatus(RoundStatus.RESULTS_PUBLISHED);
        screening.setResultsPublishedAt(at(100));
        screening.setCreatedAt(at(1));
        screening.setUpdatedAt(at(2));
        RecruitmentRound interview = new RecruitmentRound();
        interview.setName("Interview");
        interview.setMode(RoundMode.OFFLINE);
        interview.setTiming(RoundTiming.SLOTS);
        interview.setStartAt(at(200));
        interview.setEndAt(at(320));
        interview.setSlotMinutes(15);
        interview.setVenue(newId());
        interview.setMeetingLink("https://meet.example/x");
        interview.setInstructions("Bring your laptop");
        interview.setStatus(RoundStatus.SCHEDULED);
        interview.setScheduledAt(at(150));
        interview.setCreatedAt(at(3));
        interview.setUpdatedAt(at(4));

        RecruitmentPosition designer = new RecruitmentPosition();
        designer.setRole("DESIGN_LEAD");
        designer.setTitle("Design lead");
        designer.setOpenings(2);
        designer.setDescription("Owns our visual identity");
        designer.getForm().setPages(List.of(page));
        designer.setRounds(List.of(screening, interview));
        designer.setFinalizedAt(at(400));
        designer.setOfferDays(5);

        RecruitmentDrive drive = new RecruitmentDrive();
        drive.setClub(newId());
        drive.setTitle("Core team 2026");
        drive.setDescription("We are hiring");
        drive.setPositions(List.of(designer));
        drive.getEligibility().setBatches(List.of("24", "25"));
        drive.setApplicationStart(at(-1000));
        drive.setApplicationEnd(at(-10));
        drive.setClosedAt(at(-20));
        drive.setStatus(RecruitmentStatus.PUBLISHED);
        drive.setSubmittedAt(at(-2000));
        drive.setReviewedBy(newId());
        drive.setReviewedAt(at(-1500));
        drive.setReviewComment("Approved");
        drive.setPublishedAt(at(-1000));
        drive.setCompletedAt(at(500));
        drive.setCancelledAt(at(501));
        drive.setCancellationReason("n/a");
        drive.setCreatedBy(newId());
        return drive;
    }

    @Test
    void driveRoundTrip() {
        RecruitmentDrive drive = drives.save(fullDrive());

        assertSameDocument(drives.findById(drive.getId()).orElseThrow(), drive);
        Document raw = raw("recruitmentdrives");
        assertObjectIds(raw, "_id", "club", "reviewedBy", "createdBy",
                "positions.0._id", "positions.0.form.pages.0._id", "positions.0.form.pages.0.questions.0._id",
                "positions.0.rounds.0._id", "positions.0.rounds.1._id", "positions.0.rounds.1.venue");
        assertAbsent(raw, "positions.0.form._id", "eligibility._id", "positions.0.rounds.0.venue",
                "positions.0.rounds.0.timing");
        assertDates(raw, "positions.0.rounds.0.createdAt", "positions.0.rounds.1.updatedAt", "applicationEnd");
        assertThat(valueAt(raw, "positions.0.form.pages.0.questions.1.type")).isEqualTo("FILE");
        assertThat(valueAt(raw, "positions.0.rounds.1.timing")).isEqualTo("SLOTS");

        JsonNode json = toJson(drive);
        JsonNode position = json.get("positions").get(0);
        assertThat(position.get("_id").stringValue()).isEqualTo(drive.getPositions().get(0).getId());
        assertThat(position.get("form").get("pages").get(0).get("questions").get(2).get("options").size()).isEqualTo(2);
        assertThat(position.get("rounds").get(1).get("mode").stringValue()).isEqualTo("OFFLINE");
        assertThat(position.get("rounds").get(0).get("timing").isNull()).isTrue();
        assertThat(position.get("offerDays").intValue()).isEqualTo(5);
    }

    @Test
    void subDocumentsAreFoundAndUpdatedByTheirHexIds() {
        RecruitmentDrive drive = drives.save(fullDrive());
        RecruitmentPosition position = drive.getPositions().get(0);
        String roundId = position.getRounds().get(1).getId();

        // Queries convert sub-document ids (and refs) from hex strings to ObjectIds, like Mongoose casting.
        assertThat(mongo.findOne(query(where("positions._id").is(position.getId())), RecruitmentDrive.class))
                .isNotNull();
        assertThat(mongo.findOne(query(where("positions.rounds._id").is(roundId)), RecruitmentDrive.class))
                .isNotNull();
        assertThat(mongo.count(query(where("positions.rounds.venue").is(position.getRounds().get(1).getVenue())),
                        RecruitmentDrive.class))
                .isEqualTo(1);

        mongo.updateFirst(
                query(where("_id").is(drive.getId()).and("positions._id").is(position.getId())),
                new Update().set("positions.$.title", "Head of design"),
                RecruitmentDrive.class);

        assertThat(drives.findById(drive.getId()).orElseThrow().getPositions().get(0).getTitle())
                .isEqualTo("Head of design");
    }

    @Test
    void newDriveHasTheMongooseDefaults() {
        RecruitmentPosition position = new RecruitmentPosition();
        assertThat(position.getOfferDays()).isEqualTo(3);
        assertThat(position.getForm().getPages()).isEmpty();
        RecruitmentRound round = new RecruitmentRound();
        assertThat(round.getStatus()).isEqualTo(RoundStatus.DRAFT);
        assertThat(round.getInstructions()).isEmpty();
        assertThat(round.getCreatedAt()).isNotNull();
        assertThat(new RecruitmentDrive().getStatus()).isEqualTo(RecruitmentStatus.DRAFT);
    }

    // ------------------------------------------------------------------ RecruitmentApplication

    private static RecruitmentApplication application(String drive, String applicant, String position) {
        RecruitmentApplication application = new RecruitmentApplication();
        application.setDrive(drive);
        application.setClub(newId());
        application.setApplicant(applicant);
        application.setPosition(position);
        return application;
    }

    @Test
    void applicationRoundTrip() {
        String round = newId();
        RecruitmentApplication application = application(newId(), newId(), newId());
        ApplicationAnswer text = new ApplicationAnswer();
        text.setQuestion(newId());
        text.setText("I love design");
        ApplicationAnswer file = new ApplicationAnswer();
        file.setQuestion(newId());
        AnswerFile resume = new AnswerFile();
        resume.setKind(MediaKind.DOCUMENT);
        resume.setProvider("cloudinary");
        resume.setKey("cc/recruitment/resume");
        resume.setVersion(1L);
        resume.setFormat("pdf");
        resume.setName("asha-resume.pdf");
        resume.setBytes(120_000L);
        file.setFile(resume);
        ApplicationAnswer choice = new ApplicationAnswer();
        choice.setQuestion(newId());
        choice.setChoices(List.of("Design"));
        application.setAnswers(List.of(text, file, choice));
        application.setStatus(ApplicationStatus.OFFERED);
        RoundResult passed = new RoundResult();
        passed.setRound(round);
        passed.setOutcome(RoundOutcome.QUALIFIED);
        passed.setNote("Strong portfolio");
        passed.setPublishedAt(at(100));
        application.setRoundResults(List.of(passed));
        application.getPendingOutcome().setRound(newId());
        application.getPendingOutcome().setOutcome(RoundOutcome.ELIMINATED);
        application.getPendingOutcome().setNote("Not yet");
        application.setSlots(List.of(new InterviewSlot(newId(), at(200), at(215))));
        application.setRemindersSent(List.of(new SentInterviewReminder(round, "60m", at(140))));
        application.setOfferedAt(at(400));
        application.setOfferExpiresAt(at(400 + 3 * 1440));
        application.setRespondedAt(at(450));
        application.setDecidedAt(at(399));
        application.setWithdrawnAt(at(460));
        application.setClosedReason("Accepted another role");
        applications.save(application);

        assertSameDocument(applications.findById(application.getId()).orElseThrow(), application);
        Document raw = raw("recruitmentapplications");
        assertObjectIds(raw, "_id", "drive", "club", "applicant", "position", "answers.0.question",
                "answers.1.question", "roundResults.0.round", "pendingOutcome.round", "slots.0.round",
                "remindersSent.0.round");
        assertAbsent(raw, "answers.0._id", "answers.0.file", "answers.1.file._id", "roundResults.0._id",
                "slots.0._id", "remindersSent.0._id", "pendingOutcome._id");
        assertThat(valueAt(raw, "answers.1.file.kind")).isEqualTo("DOCUMENT");
        assertThat(valueAt(raw, "pendingOutcome.outcome")).isEqualTo("ELIMINATED");

        JsonNode json = toJson(application);
        assertThat(json.get("answers").get(1).get("file").get("name").stringValue()).isEqualTo("asha-resume.pdf");
        assertThat(json.get("answers").get(0).get("file").isNull()).isTrue();
        assertThat(json.get("slots").get(0).get("startAt").stringValue()).isEqualTo(at(200).toString());
        assertThat(json.get("status").stringValue()).isEqualTo("OFFERED");
        assertThat(applications.findByDriveAndApplicantAndPosition(
                        application.getDrive(), application.getApplicant(), application.getPosition()))
                .isPresent();
    }

    @Test
    void oneApplicationPerStudentPerRole() {
        String drive = newId();
        String student = newId();
        String role = newId();
        applications.save(application(drive, student, role));

        assertThatThrownBy(() -> applications.save(application(drive, student, role)))
                .isInstanceOf(DuplicateKeyException.class);
        applications.save(application(drive, student, newId())); // another role of the same drive

        RecruitmentApplication fresh = applications.findAll().get(0);
        assertThat(fresh.getPendingOutcome()).isNotNull();
        assertThat(fresh.getPendingOutcome().getOutcome()).isNull();
        assertThat(fresh.getCreatedAt()).isBefore(Instant.now().plusSeconds(1));
    }
}
