package com.campusconnect.feature.hackathon;

import static com.campusconnect.feature.hackathon.HackathonSupport.fail;
import static com.campusconnect.feature.hackathon.HackathonSupport.meta;
import static com.campusconnect.feature.hackathon.HackathonSupport.ms;
import static com.campusconnect.feature.hackathon.HackathonSupport.oid;
import static com.campusconnect.feature.hackathon.JsValues.get;
import static com.campusconnect.feature.hackathon.JsValues.string;
import static com.campusconnect.feature.hackathon.JsValues.truthy;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.ParticipationMode;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.result.Hackathon;
import com.campusconnect.domain.result.HackathonEntry;
import com.campusconnect.domain.result.HackathonJudge;
import com.campusconnect.domain.result.JudgeScore;
import com.campusconnect.domain.result.JudgingCriterion;
import com.campusconnect.domain.result.ScoreMark;
import com.campusconnect.feature.hackathon.HackathonDtos.CriterionAverage;
import com.campusconnect.feature.hackathon.HackathonDtos.DraftBody;
import com.campusconnect.feature.hackathon.HackathonDtos.JudgeComment;
import com.campusconnect.feature.hackathon.HackathonDtos.JudgingEntry;
import com.campusconnect.feature.hackathon.HackathonDtos.JudgingPanel;
import com.campusconnect.feature.hackathon.HackathonDtos.Leaderboard;
import com.campusconnect.feature.hackathon.HackathonDtos.LeaderboardRow;
import com.campusconnect.feature.hackathon.HackathonDtos.MyScore;
import com.campusconnect.feature.hackathon.HackathonDtos.ScoreBody;
import com.campusconnect.feature.hackathon.HackathonDtos.Stats;
import com.campusconnect.feature.hackathon.HackathonSupport.Access;
import com.campusconnect.feature.result.ResultDtos.AwardBody;
import com.campusconnect.feature.result.ResultDtos.EntryBody;
import com.campusconnect.feature.result.ResultDtos.NewRoundBody;
import com.campusconnect.feature.result.ResultDtos.ResultBody;
import com.campusconnect.feature.result.ResultDtos.RoundChangesBody;
import com.campusconnect.feature.result.ResultDtos.RoundView;
import com.campusconnect.feature.result.ResultService;
import com.mongodb.client.result.UpdateResult;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Judging and results (services/HackathonService.js): judges score every submitted project on the criteria, the
 * leaderboard averages the judges' totals for the organisers and the mentor, and is turned into the event's results
 * draft. A judge's score replaces only that judge's earlier score in one atomic update, so judges scoring the same
 * project at the same moment never lose each other's marks (the Node app rewrote the whole list).
 */
@Service
public class HackathonJudging {

    private static final List<String> DEFAULT_TITLES = List.of("Winner", "1st runner-up", "2nd runner-up");
    private static final String JUDGING_ROUND = "Judging";
    private static final String JUDGING_ROUND_DESCRIPTION = "Final judging scores (average of the judges).";

    private final MongoOperations mongo;
    private final HackathonSupport support;
    private final ResultService results;
    private final Refs refs;

    public HackathonJudging(MongoOperations mongo, HackathonSupport support, ResultService results, Refs refs) {
        this.mongo = mongo;
        this.support = support;
        this.results = results;
        this.refs = refs;
    }

    private static void assertJudge(AuthUser actor, Hackathon hackathon) {
        if (!HackathonSupport.isJudge(hackathon, actor.id())) {
            throw AppException.forbidden("Only this hackathon's judges can score projects");
        }
    }

    private List<HackathonEntry> submittedEntries(String eventId) {
        return mongo.find(Query.query(Criteria.where("event").is(oid(eventId)).and("submittedAt").ne(null)).with(Sort.by("submittedAt")), HackathonEntry.class);
    }

    /** {@code String(value?._id || value || "")}. */
    private static String idOf(Object value) {
        Object id = get(value, "_id");
        return truthy(id) ? string(id) : truthy(value) ? string(value) : "";
    }

    // ---------------------------------------------------------------- judges

    /** The judge's panel: every submitted project, with the judge's own scores. */
    public JudgingPanel listForJudging(AuthUser actor, String eventId) {
        Event event = support.loadEvent(eventId);
        Hackathon hackathon = support.ensure(event);
        assertJudge(actor, hackathon);
        return panel(actor, event, hackathon);
    }

    private JudgingPanel panel(AuthUser actor, Event event, Hackathon hackathon) {
        boolean open = HackathonSupport.judgingOpen(event, hackathon, Instant.now());
        List<HackathonEntry> entries = open ? submittedEntries(event.getId()) : List.of();
        List<JudgingEntry> views = new ArrayList<>();
        int scored = 0;
        for (HackathonEntry entry : entries) {
            JudgeScore mine = entry.getScores().stream().filter(score -> actor.id().equals(score.getJudge())).findFirst().orElse(null);
            if (mine != null) {
                scored++;
            }
            views.add(new JudgingEntry(HackathonSupport.entryView(entry, hackathon),
                    mine == null ? null : new MyScore(mine.getMarks(), mine.getTotal(), mine.getComment(), mine.getScoredAt())));
        }
        return new JudgingPanel(open, hackathon.getSubmissionDeadline(), hackathon.getCriteria(),
                hackathon.getSubmissionQuestions() == null ? List.of() : hackathon.getSubmissionQuestions(), HackathonSupport.maxTotal(hackathon), views, scored);
    }

    public JudgingPanel scoreEntry(AuthUser actor, String eventId, String entryId, ScoreBody body) {
        Event event = support.loadEvent(eventId);
        Hackathon hackathon = support.ensure(event);
        assertJudge(actor, hackathon);
        if (!HackathonSupport.judgingOpen(event, hackathon, Instant.now())) {
            throw AppException.conflict("Judging opens when the submission deadline passes", ErrorCode.INVALID_STATE);
        }
        if (event.getStatus() == EventStatus.CANCELLED) {
            throw AppException.conflict("This hackathon was cancelled", ErrorCode.INVALID_STATE);
        }
        Document submitted = new Document("_id", oid(entryId)).append("event", oid(event.getId())).append("submittedAt", new Document("$ne", null));
        if (!mongo.exists(Query.query(Criteria.where("_id").is(oid(entryId)).and("event").is(oid(event.getId())).and("submittedAt").ne(null)), HackathonSupport.ENTRIES)) {
            throw AppException.notFound("Submission not found");
        }

        Map<String, Double> given = new HashMap<>();
        for (Object mark : body.marks()) {
            given.put(idOf(get(mark, "criterion")), JsValues.number(get(mark, "score")));
        }
        List<Document> marks = new ArrayList<>();
        double total = 0;
        for (JudgingCriterion criterion : hackathon.getCriteria()) {
            Double score = given.get(criterion.getId());
            if (score == null || !Double.isFinite(score) || score < 0 || score > criterion.getMaxScore()) {
                throw fail("Give \"" + criterion.getName() + "\" a score from 0 to " + criterion.getMaxScore());
            }
            double rounded = Math.round(score * 10) / 10.0;
            marks.add(new Document("criterion", oid(criterion.getId())).append("score", rounded));
            total += rounded;
        }
        String comment = JsValues.cut(JsValues.trim(JsValues.text(body.comment())), 1000);
        Document score = new Document("judge", oid(actor.id())).append("marks", marks).append("total", total).append("comment", comment).append("scoredAt", new Date());

        // Drop this judge's earlier score and append the new one in a single atomic update (no lost marks).
        Document others = new Document("$filter", new Document("input", new Document("$ifNull", List.of("$scores", List.of())))
                .append("cond", new Document("$ne", List.of("$$this.judge", oid(actor.id())))));
        Document replace = new Document("$set", new Document("scores", new Document("$concatArrays", List.of(others, new Document("$literal", List.of(score)))))
                .append("updatedAt", new Date()));
        UpdateResult written = mongo.getCollection(HackathonSupport.ENTRIES).updateOne(submitted, List.of(replace));
        if (written.getMatchedCount() == 0) {
            throw AppException.notFound("Submission not found");
        }
        support.audit(event, AuditAction.HACKATHON_SCORED, actor, meta("entry", oid(entryId), "total", total));
        return panel(actor, event, hackathon);
    }

    // ---------------------------------------------------------------- leaderboard

    private record Ranked(HackathonEntry entry, Double average, int judges, Double percent, Integer rank) {
    }

    /** Average of the judges' totals, best first; ties go to the earlier submission. */
    private static List<Ranked> rank(List<HackathonEntry> entries, Hackathon hackathon) {
        int maxTotal = HackathonSupport.maxTotal(hackathon);
        double max = maxTotal == 0 ? 1 : maxTotal;
        List<Ranked> rows = new ArrayList<>();
        for (HackathonEntry entry : entries) {
            List<Double> totals = entry.getScores().stream().map(JudgeScore::getTotal).toList();
            Double average = null;
            if (!totals.isEmpty()) {
                double sum = 0;
                for (double value : totals) {
                    sum += value;
                }
                average = sum / totals.size();
            }
            rows.add(new Ranked(entry, average, totals.size(), average == null ? null : Math.round(average / max * 1000) / 10.0, null));
        }
        rows.sort(Comparator.comparingDouble((Ranked row) -> row.average() == null ? -1 : row.average()).reversed()
                .thenComparingLong(row -> ms(row.entry().getSubmittedAt())));
        List<Ranked> ranked = new ArrayList<>();
        for (int index = 0; index < rows.size(); index++) {
            Ranked row = rows.get(index);
            ranked.add(new Ranked(row.entry(), row.average(), row.judges(), row.percent(), row.average() == null ? null : index + 1));
        }
        return ranked;
    }

    public Leaderboard getLeaderboard(AuthUser actor, String eventId) {
        Event event = support.loadEvent(eventId);
        Hackathon hackathon = support.ensure(event);
        Access access = support.accessFor(actor, event, hackathon);
        if (!access.staff() && !access.mentor() && !access.resultsManager()) {
            throw AppException.forbidden("The leaderboard is for the organisers and the mentor");
        }
        return leaderboard(event, hackathon);
    }

    private long registeredEntries(Event event) {
        Criteria registered = Criteria.where("event").is(oid(event.getId())).and("status").is(RegistrationStatus.REGISTERED.name());
        if (event.getParticipationMode() == ParticipationMode.TEAM) {
            return mongo.findDistinct(Query.query(registered.and("team").ne(null)), "team", "eventregistrations", Object.class).size();
        }
        return mongo.count(Query.query(registered), "eventregistrations");
    }

    private Leaderboard leaderboard(Event event, Hackathon hackathon) {
        List<HackathonEntry> entries = mongo.find(Query.query(Criteria.where("event").is(oid(event.getId()))).with(Sort.by("submittedAt")), HackathonEntry.class);
        long registered = registeredEntries(event);
        List<HackathonEntry> submitted = entries.stream().filter(entry -> entry.getSubmittedAt() != null).toList();
        Map<String, Map<String, Object>> judges = refs.users(hackathon.getJudges().stream().map(HackathonJudge::getUser).toList(), "name");
        int panel = hackathon.getJudges().size();

        Stats stats = new Stats(
                registered,
                (int) entries.stream().filter(entry -> entry.getProblemStatement() != null).count(),
                (int) entries.stream().filter(entry -> entry.getRepoSubmittedAt() != null).count(),
                submitted.size(),
                (int) submitted.stream().filter(entry -> entry.getScores().size() >= panel && panel > 0).count());

        List<LeaderboardRow> rows = new ArrayList<>();
        for (Ranked row : rank(submitted, hackathon)) {
            HackathonEntry entry = row.entry();
            List<CriterionAverage> byCriterion = new ArrayList<>();
            for (JudgingCriterion criterion : hackathon.getCriteria()) {
                List<Double> values = new ArrayList<>();
                for (JudgeScore score : entry.getScores()) {
                    score.getMarks().stream().filter(mark -> criterion.getId().equals(mark.getCriterion())).findFirst().map(ScoreMark::getScore).ifPresent(values::add);
                }
                Double average = null;
                if (!values.isEmpty()) {
                    double sum = 0;
                    for (double value : values) {
                        sum += value;
                    }
                    average = Math.round(sum / values.size() * 10) / 10.0;
                }
                byCriterion.add(new CriterionAverage(criterion.getId(), average));
            }
            List<JudgeComment> comments = new ArrayList<>();
            for (JudgeScore score : entry.getScores()) {
                if (score.getComment() != null && !score.getComment().isEmpty()) {
                    Map<String, Object> judge = judges.get(score.getJudge());
                    Object name = judge == null ? null : judge.get("name");
                    comments.add(new JudgeComment(truthy(name) ? String.valueOf(name) : "Judge", score.getComment()));
                }
            }
            rows.add(new LeaderboardRow(HackathonSupport.entryView(entry, hackathon), row.rank(),
                    row.average() == null ? null : Math.round(row.average() * 10) / 10.0, row.percent(), row.judges(), byCriterion, comments));
        }
        return new Leaderboard(hackathon.getCriteria(), HackathonSupport.maxTotal(hackathon), panel, stats, HackathonSupport.judgingOpen(event, hackathon, Instant.now()),
                hackathon.getResultsDraftedAt(), rows);
    }

    // ---------------------------------------------------------------- results

    /** Turns the leaderboard into the results draft: awards for the top entries and a "Judging" round with every ranked team. */
    public Leaderboard draftResults(AuthUser actor, String eventId, DraftBody input) {
        DraftBody body = input == null ? DraftBody.EMPTY : input;
        Event event = support.loadEvent(eventId);
        Hackathon hackathon = support.ensure(event);
        double winners = body.winners() == null ? 3 : body.winners().doubleValue();
        if (!JsValues.isInteger(winners) || winners < 1 || winners > 10) {
            throw fail("Choose between 1 and 10 winners");
        }
        if (!HackathonSupport.judgingOpen(event, hackathon, Instant.now())) {
            throw AppException.conflict("Results can be prepared after the submission deadline", ErrorCode.INVALID_STATE);
        }
        List<Ranked> ranked = rank(submittedEntries(event.getId()), hackathon).stream().filter(row -> row.average() != null).toList();
        if (ranked.isEmpty()) {
            throw AppException.conflict("No project has been scored yet", ErrorCode.INVALID_STATE);
        }

        boolean isTeam = event.getParticipationMode() == ParticipationMode.TEAM;
        int maxTotal = HackathonSupport.maxTotal(hackathon);
        int count = (int) winners;
        List<AwardBody> awards = new ArrayList<>();
        for (int index = 0; index < Math.min(count, ranked.size()); index++) {
            Ranked row = ranked.get(index);
            Object given = body.titles() != null && index < body.titles().size() ? body.titles().get(index) : JsValues.UNDEFINED;
            String title = truthy(given) ? string(given) : index < DEFAULT_TITLES.size() ? DEFAULT_TITLES.get(index) : "Rank " + (index + 1);
            awards.add(new AwardBody(JsValues.cut(title, 120), index + 1, row.entry().getOwner(), null, isTeam ? row.entry().getName() : null, null,
                    projectTitle(row.entry()) + " · " + JsValues.numberToString(row.average()) + "/" + maxTotal));
        }
        int judged = ranked.size();
        int panel = hackathon.getJudges().size();
        String summary = judged + " project" + (judged == 1 ? "" : "s") + " judged by " + panel + " judge" + (panel == 1 ? "" : "s") + " on "
                + hackathon.getCriteria().stream().map(criterion -> criterion.getName().toLowerCase(Locale.ROOT)).collect(Collectors.joining(", ")) + ".";

        results.upsertResult(actor, event.getId(), new ResultBody(summary, awards));
        List<EntryBody> standings = ranked.stream()
                .map(row -> new EntryBody(row.rank(), row.entry().getOwner(), null, isTeam ? row.entry().getName() : null,
                        JsValues.numberToString(row.average()) + "/" + maxTotal, null, JsValues.cut(projectTitle(row.entry()), 200)))
                .toList();
        RoundView existing = results.getResult(actor, event.getId()).rounds().stream().filter(round -> JUDGING_ROUND.equals(round.name())).findFirst().orElse(null);
        if (existing != null) {
            results.updateRound(actor, event.getId(), existing.id(), new RoundChangesBody(JUDGING_ROUND, JUDGING_ROUND_DESCRIPTION, standings));
        } else {
            results.createRound(actor, event.getId(), new NewRoundBody(JUDGING_ROUND, JUDGING_ROUND_DESCRIPTION, standings));
        }
        Instant now = Instant.now();
        mongo.updateFirst(Query.query(Criteria.where("_id").is(oid(hackathon.getId()))), new Update().set("resultsDraftedAt", now).set("updatedAt", now),
                HackathonSupport.COLLECTION);
        support.audit(event, AuditAction.HACKATHON_RESULTS_DRAFTED, actor, meta("winners", count));
        return leaderboard(event, support.find(event.getId()));
    }

    private static String projectTitle(HackathonEntry entry) {
        return entry.getProject() == null || entry.getProject().getTitle() == null ? "" : entry.getProject().getTitle();
    }
}
