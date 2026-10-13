package com.campusconnect.feature.hackathon;

import com.campusconnect.domain.common.FormAnswer;
import com.campusconnect.domain.common.FormQuestion;
import com.campusconnect.domain.result.AgendaItem;
import com.campusconnect.domain.result.HackathonProject;
import com.campusconnect.domain.result.JudgingCriterion;
import com.campusconnect.domain.result.ScoreMark;
import com.campusconnect.domain.result.SubmissionFields;
import com.campusconnect.feature.result.WholeNumber;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;

/**
 * Request bodies of the hackathon routes (the validators of routes/EventRoutes.js; everything else is checked by the
 * service with HackathonService.js's messages) and the JSON views of the hackathon pages. Most fields are loosely
 * typed because the Node service reads them with JavaScript conversions (see {@link JsValues}); {@code null} means
 * "not sent".
 */
public final class HackathonDtos {

    static final String MONGO_ID = "^[a-fA-F0-9]{24}$";

    private HackathonDtos() {
    }

    // ---------------------------------------------------------------- requests

    /** PUT /api/events/:id/hackathon: deadlines, agenda, criteria and the submission form. */
    public record SettingsBody(
            @Size(max = 40, message = "Invalid value") List<Object> agenda,
            @Size(max = 10, message = "Invalid value") List<Object> criteria,
            @Size(max = 25, message = "Invalid value") List<Object> submissionQuestions,
            Object revealAt,
            Object selectionDeadline,
            Object repoDeadline,
            Object submissionDeadline,
            Object submissionFields) {

        static final SettingsBody EMPTY = new SettingsBody(null, null, null, null, null, null, null, null);
    }

    public record ProblemBody(Object title, Object description, Object track, Object maxTeams) {

        static final ProblemBody EMPTY = new ProblemBody(null, null, null, null);
    }

    public record JudgeBody(@NotNull(message = "Choose a judge") @Pattern(regexp = MONGO_ID, message = "Choose a judge") String userId) {
    }

    public record ProblemChoice(
            @NotNull(message = "Choose a problem statement") @Pattern(regexp = MONGO_ID, message = "Choose a problem statement") String problemId) {
    }

    public record RepoBody(
            @NotNull(message = "Invalid value. Add the repository link") @Size(min = 8, max = 500, message = "Add the repository link") String repoUrl) {
    }

    /** The final submission; the repository link can't be changed here (it is locked at the repository deadline). */
    public record ProjectBody(Object title, Object summary, Object demoUrl, Object videoUrl, Object deckUrl, Object techStack, Object answers) {

        static final ProjectBody EMPTY = new ProjectBody(null, null, null, null, null, null, null);
    }

    public record ScoreBody(
            @NotNull(message = "Invalid value") @Size(max = 10, message = "Invalid value") List<Object> marks,
            @Size(max = 1000, message = "Invalid value") String comment) {
    }

    /** POST /api/events/:id/hackathon/results: how many winners, and their award titles. */
    public record DraftBody(@WholeNumber(min = 1, max = 10) Number winners, @Size(max = 10, message = "Invalid value") List<Object> titles) {

        static final DraftBody EMPTY = new DraftBody(null, null);
    }

    // ---------------------------------------------------------------- views

    public record ProblemRef(@JsonProperty("_id") String id, String title) {
    }

    /** A team's (or individual's) entry as the pages show it (entryView). */
    public record EntryView(
            @JsonProperty("_id") String id,
            String name,
            ProblemRef problemStatement,
            Instant problemChosenAt,
            HackathonProject project,
            List<FormAnswer> submissionAnswers,
            Instant repoSubmittedAt,
            Instant submittedAt,
            Instant updatedAt) {
    }

    /** The viewer's entry, with the size of their team. */
    public record MyEntry(@JsonUnwrapped EntryView entry, int members) {
    }

    /** A registered participant whose team has not done anything yet (no entry stored). */
    public record PendingEntry(String name, Object problemStatement, Object project, Instant repoSubmittedAt, Instant submittedAt, int members) {
    }

    public record ProblemView(@JsonProperty("_id") String id, String title, String description, String track, Integer maxTeams, int teams) {
    }

    public record JudgeView(@JsonProperty("_id") String id, String name, String accountType, String departmentCode, String avatar) {
    }

    public record HackathonViewer(
            @JsonProperty("isParticipant") boolean participant,
            boolean canManage,
            boolean canJudge,
            boolean canSeeLeaderboard,
            boolean canDraftResults,
            boolean canChooseProblem,
            boolean canSubmitRepo,
            boolean canSubmit) {
    }

    /** GET /api/events/:id/hackathon — everything the event page shows about the hackathon, shaped for the viewer. */
    public record HackathonView(
            String eventId,
            String phase,
            Instant revealAt,
            Instant selectionDeadline,
            Instant repoDeadline,
            Instant submissionDeadline,
            List<AgendaItem> agenda,
            List<JudgingCriterion> criteria,
            SubmissionFields submissionFields,
            List<FormQuestion> submissionQuestions,
            int maxTotal,
            int problemCount,
            List<ProblemView> problemStatements,
            int judgeCount,
            List<JudgeView> judges,
            Instant resultsDraftedAt,
            Object myEntry,
            HackathonViewer viewer) {
    }

    public record MyScore(List<ScoreMark> marks, double total, String comment, Instant scoredAt) {
    }

    public record JudgingEntry(@JsonUnwrapped EntryView entry, MyScore myScore) {
    }

    /** GET /api/events/:id/hackathon/judging — the judge's panel. */
    public record JudgingPanel(
            boolean open,
            Instant opensAt,
            List<JudgingCriterion> criteria,
            List<FormQuestion> submissionQuestions,
            int maxTotal,
            List<JudgingEntry> entries,
            int scored) {
    }

    public record Stats(long entries, int chosen, int repos, int submitted, int fullyScored) {
    }

    public record CriterionAverage(String criterion, Double average) {
    }

    public record JudgeComment(String judge, String comment) {
    }

    public record LeaderboardRow(
            @JsonUnwrapped EntryView entry,
            Integer rank,
            Double average,
            Double percent,
            int judges,
            List<CriterionAverage> byCriterion,
            List<JudgeComment> comments) {
    }

    /** GET /api/events/:id/hackathon/leaderboard — for the organisers and the mentor. */
    public record Leaderboard(
            List<JudgingCriterion> criteria,
            int maxTotal,
            int judgeCount,
            Stats stats,
            boolean judgingOpen,
            Instant resultsDraftedAt,
            List<LeaderboardRow> rows) {
    }
}
