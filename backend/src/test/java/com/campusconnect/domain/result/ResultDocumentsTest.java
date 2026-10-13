package com.campusconnect.domain.result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.common.FormQuestion;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class ResultDocumentsTest extends MongoDocumentTest {

    @Autowired
    EventResultRepository results;

    @Autowired
    HackathonRepository hackathons;

    @Autowired
    HackathonEntryRepository entries;

    @Autowired
    CertificateRepository certificates;

    // ------------------------------------------------------------------ EventResult

    private static EventResult result(String event) {
        EventResult result = new EventResult();
        result.setEvent(event);
        result.setClub(newId());
        result.setCreatedBy(newId());
        return result;
    }

    private static EventResult fullResult() {
        EventResult result = result(newId());
        result.setSummary("A close final.");
        ResultAward winner = new ResultAward();
        winner.setTitle("Winner");
        winner.setPosition(1);
        winner.setRecipientName("Asha");
        winner.setRecipientUser(newId());
        winner.setTeamName("Byte Me");
        winner.setPrize("₹10,000");
        winner.setRecognition("Best overall");
        result.setAwards(List.of(winner));

        ResultEntry through = new ResultEntry();
        through.setRank(1);
        through.setRecipientUser(newId());
        through.setRecipientName("Asha");
        through.setTeamName("Byte Me");
        through.setScore("34/40");
        through.setQualified(true);
        through.setNote("Strong demo");
        ResultEntry unranked = new ResultEntry();
        unranked.setRecipientName("Guest team");
        ResultRound round = new ResultRound();
        round.setName("Screening");
        round.setDescription("Ideas on paper");
        round.setEntries(List.of(through, unranked));
        round.setStatus(ResultStatus.PUBLISHED);
        round.setPublishedAt(at(10));
        round.setPublishedBy(newId());
        round.setCorrectedAt(at(20));
        round.setCreatedBy(newId());
        round.setUpdatedBy(newId());
        round.setCreatedAt(at(1));
        round.setUpdatedAt(at(2));
        result.setRounds(List.of(round));

        result.setStatus(ResultStatus.PUBLISHED);
        result.setUpdatedBy(newId());
        result.setPublishedAt(at(30));
        result.setPublishedBy(newId());
        result.setCorrectedAt(at(40));
        result.setLastPublishedAt(at(40));
        return result;
    }

    @Test
    void resultRoundTrip() {
        EventResult result = results.save(fullResult());

        assertSameDocument(results.findById(result.getId()).orElseThrow(), result);
        assertThat(results.findByEvent(result.getEvent())).map(EventResult::getId).contains(result.getId());

        Document raw = raw("eventresults");
        assertObjectIds(raw, "_id", "event", "club", "createdBy", "updatedBy", "publishedBy",
                "awards.0._id", "awards.0.recipientUser",
                "rounds.0._id", "rounds.0.publishedBy", "rounds.0.createdBy", "rounds.0.updatedBy",
                "rounds.0.entries.0._id", "rounds.0.entries.1._id", "rounds.0.entries.0.recipientUser");
        // Rounds keep their own Mongoose timestamps.
        assertDates(raw, "rounds.0.createdAt", "rounds.0.updatedAt", "lastPublishedAt");
        assertAbsent(raw, "rounds.0.entries.1.qualified", "rounds.0.entries.1.rank");
        assertThat(valueAt(raw, "rounds.0.entries.0.qualified")).isEqualTo(true);

        JsonNode json = toJson(result);
        JsonNode round = json.get("rounds").get(0);
        assertThat(round.get("_id").stringValue()).isEqualTo(result.getRounds().get(0).getId());
        assertThat(round.get("createdAt").stringValue()).isEqualTo(at(1).toString());
        assertThat(round.get("entries").get(1).get("qualified").isNull()).isTrue();
        assertThat(json.get("awards").get(0).get("position").intValue()).isEqualTo(1);
    }

    @Test
    void newRoundsGetTheirTimestampsAndTouchMovesUpdatedAt() throws InterruptedException {
        ResultRound round = new ResultRound();
        assertThat(round.getCreatedAt()).isNotNull().isEqualTo(round.getUpdatedAt());
        assertThat(round.getId()).hasSize(24);

        Thread.sleep(2);
        round.touch();

        assertThat(round.getUpdatedAt()).isAfter(round.getCreatedAt());
    }

    @Test
    void oneResultPerEvent() {
        EventResult first = results.save(result(newId()));

        assertThatThrownBy(() -> results.save(result(first.getEvent()))).isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------ Hackathon

    private static Hackathon hackathon(String event) {
        Hackathon hackathon = new Hackathon();
        hackathon.setEvent(event);
        hackathon.setClub(newId());
        hackathon.setRevealAt(at(0));
        hackathon.setSelectionDeadline(at(60));
        hackathon.setSubmissionDeadline(at(720));
        return hackathon;
    }

    @Test
    void hackathonRoundTrip() {
        Hackathon hackathon = hackathon(newId());
        AgendaItem kickoff = new AgendaItem();
        kickoff.setTitle("Kick-off");
        kickoff.setStartsAt(at(0));
        kickoff.setNote("Main hall");
        hackathon.setAgenda(List.of(kickoff));
        ProblemStatement problem = new ProblemStatement();
        problem.setTitle("Campus navigation");
        problem.setDescription("Help freshers find rooms");
        problem.setTrack("Mobility");
        problem.setMaxTeams(5);
        hackathon.setProblemStatements(List.of(problem));
        hackathon.setRepoDeadline(at(360));
        JudgingCriterion innovation = new JudgingCriterion();
        innovation.setName("Innovation");
        innovation.setMaxScore(10);
        hackathon.setCriteria(List.of(innovation));
        hackathon.getSubmissionFields().setDemoUrl(SubmissionFieldSetting.REQUIRED);
        hackathon.getSubmissionFields().setDeckUrl(SubmissionFieldSetting.OFF);
        FormQuestion apis = new FormQuestion();
        apis.setType("SHORT");
        apis.setLabel("Which APIs did you use?");
        hackathon.setSubmissionQuestions(List.of(apis));
        HackathonJudge judge = new HackathonJudge();
        judge.setUser(newId());
        judge.setAddedBy(newId());
        judge.setAddedAt(at(-100));
        hackathon.setJudges(List.of(judge));
        hackathon.setNotified(List.of("REVEAL", "SELECTION_1H"));
        hackathon.setResultsDraftedAt(at(800));
        hackathon.setUpdatedBy(newId());
        hackathons.save(hackathon);

        assertSameDocument(hackathons.findById(hackathon.getId()).orElseThrow(), hackathon);
        assertThat(hackathons.findByEvent(hackathon.getEvent())).isPresent();
        Document raw = raw("hackathons");
        assertObjectIds(raw, "_id", "event", "club", "updatedBy", "judges.0.user", "judges.0.addedBy",
                "agenda.0._id", "problemStatements.0._id", "criteria.0._id", "submissionQuestions.0._id");
        assertAbsent(raw, "judges.0._id", "submissionFields._id", "submissionQuestions.0.scope");
        assertThat(valueAt(raw, "submissionFields.demoUrl")).isEqualTo("REQUIRED");
        assertThat(valueAt(raw, "submissionFields.videoUrl")).isEqualTo("OPTIONAL");

        JsonNode json = toJson(hackathon);
        // Hackathon questions have no scope (FormQuestion leaves it out of the JSON when null).
        assertThat(json.get("submissionQuestions").get(0).has("scope")).isFalse();
        assertThat(json.get("submissionQuestions").get(0).get("_id").stringValue()).isEqualTo(apis.getId());
        assertThat(json.get("submissionFields").get("deckUrl").stringValue()).isEqualTo("OFF");
        assertThat(json.get("problemStatements").get(0).get("maxTeams").intValue()).isEqualTo(5);

        assertThatThrownBy(() -> hackathons.save(hackathon(hackathon.getEvent())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    // ------------------------------------------------------------------ HackathonEntry

    private static HackathonEntry entry(String event, String key) {
        HackathonEntry entry = new HackathonEntry();
        entry.setEvent(event);
        entry.setEntryKey(key);
        entry.setOwner(newId());
        entry.setName("Byte Me");
        return entry;
    }

    @Test
    void hackathonEntryRoundTrip() {
        String team = newId();
        HackathonEntry entry = entry(newId(), "team:" + team);
        entry.setTeam(team);
        entry.setProblemStatement(newId());
        entry.setProblemChosenAt(at(30));
        entry.setProblemChosenBy(newId());
        entry.getProject().setTitle("WayFinder");
        entry.getProject().setSummary("Indoor maps");
        entry.getProject().setRepoUrl("https://github.com/x/wayfinder");
        entry.getProject().setDemoUrl("https://wayfinder.dev");
        entry.getProject().setVideoUrl("https://youtu.be/x");
        entry.getProject().setDeckUrl("https://slides.example/x");
        entry.getProject().setTechStack("React, Spring");
        entry.setRepoSubmittedAt(at(300));
        entry.setRepoSubmittedBy(newId());
        entry.setSubmissionAnswers(List.of(new FormAnswer(newId(), "Maps API", List.of())));
        entry.setSubmittedAt(at(700));
        entry.setSubmittedBy(newId());
        JudgeScore score = new JudgeScore();
        score.setJudge(newId());
        score.setMarks(List.of(new ScoreMark(newId(), 8.5), new ScoreMark(newId(), 7)));
        score.setTotal(15.5);
        score.setComment("Polished");
        score.setScoredAt(at(750));
        entry.setScores(List.of(score));
        entries.save(entry);

        assertSameDocument(entries.findById(entry.getId()).orElseThrow(), entry);
        assertThat(entries.findByEventAndEntryKey(entry.getEvent(), entry.getEntryKey())).isPresent();
        Document raw = raw("hackathonentries");
        assertObjectIds(raw, "_id", "event", "team", "owner", "problemStatement", "problemChosenBy",
                "repoSubmittedBy", "submittedBy", "submissionAnswers.0.question",
                "scores.0.judge", "scores.0.marks.0.criterion", "scores.0.marks.1.criterion");
        assertAbsent(raw, "scores.0._id", "scores.0.marks.0._id", "project._id");
        assertThat(valueAt(raw, "scores.0.marks.0.score")).isEqualTo(8.5);
        JsonNode json = toJson(entry);
        assertThat(json.get("project").get("title").stringValue()).isEqualTo("WayFinder");
        assertThat(json.get("scores").get(0).get("total").doubleValue()).isEqualTo(15.5);
        assertThat(json.get("problemStatement").stringValue()).isEqualTo(entry.getProblemStatement());

        assertThatThrownBy(() -> entries.save(entry(entry.getEvent(), entry.getEntryKey())))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void newEntryHasAnEmptyProject() {
        HackathonEntry entry = entries.save(entry(newId(), "user:" + newId()));

        assertThat(valueAt(raw("hackathonentries"), "project.repoUrl")).isEqualTo("");
        assertAbsent(raw("hackathonentries"), "team", "problemStatement", "submittedAt");
        assertThat(toJson(entry).get("team").isNull()).isTrue();
    }

    // ------------------------------------------------------------------ Certificate

    private static Certificate certificate(String code, String event, String user, CertificateKind kind) {
        Certificate certificate = new Certificate();
        certificate.setCode(code);
        certificate.setEvent(event);
        certificate.setClub(newId());
        certificate.setUser(user);
        certificate.setKind(kind);
        certificate.setRecipientName("Asha");
        certificate.setEventTitle("HackNight");
        certificate.setClubName("Coding Club");
        certificate.setEventStartAt(at(0));
        certificate.setEventEndAt(at(720));
        return certificate;
    }

    @Test
    void certificateRoundTripAndUniqueness() {
        String event = newId();
        String user = newId();
        Certificate merit = certificate("CC-7Q2X-91", event, user, CertificateKind.MERIT);
        merit.setAwardTitle("Winner");
        merit.setTeamName("Byte Me");
        merit.setIssuedAt(at(800));
        merit.setRevokedAt(at(900));
        certificates.save(merit);

        assertSameDocument(certificates.findByCode("CC-7Q2X-91").orElseThrow(), merit);
        assertObjectIds(raw("certificates"), "_id", "event", "club", "user");
        assertThat(toJson(merit).get("kind").stringValue()).isEqualTo("MERIT");

        certificates.save(certificate("CC-0000-01", event, user, CertificateKind.PARTICIPATION));
        assertThatThrownBy(() -> certificates.save(certificate("CC-0000-02", event, user, CertificateKind.MERIT)))
                .isInstanceOf(DuplicateKeyException.class);
        assertThatThrownBy(() -> certificates.save(
                        certificate("CC-7Q2X-91", newId(), newId(), CertificateKind.PARTICIPATION)))
                .isInstanceOf(DuplicateKeyException.class);
    }
}
