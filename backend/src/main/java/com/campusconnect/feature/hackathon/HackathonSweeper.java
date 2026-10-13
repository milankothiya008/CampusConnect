package com.campusconnect.feature.hackathon;

import static com.campusconnect.feature.hackathon.HackathonSupport.HOUR;
import static com.campusconnect.feature.hackathon.HackathonSupport.ms;
import static com.campusconnect.feature.hackathon.HackathonSupport.oid;

import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.result.Hackathon;
import com.campusconnect.domain.result.HackathonJudge;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Time-based hackathon notifications (HackathonService.sweepHackathons, run by the Node event sweeper every minute):
 * problems are live, an hour left to choose / add the repository / submit, final submissions open, judging open.
 * Each one goes out once per hackathon: it is claimed atomically in {@code Hackathon.notified} before sending, and
 * ShedLock lets one replica run each tick.
 */
@Slf4j
@Component
public class HackathonSweeper {

    private final MongoOperations mongo;
    private final NotificationService notifications;

    public HackathonSweeper(MongoOperations mongo, NotificationService notifications) {
        this.mongo = mongo;
        this.notifications = notifications;
    }

    private record Step(String kind, boolean due, Supplier<List<String>> to, Supplier<String> title, Supplier<String> message, String link) {
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @SchedulerLock(name = "hackathonSweep", lockAtMostFor = "PT5M", lockAtLeastFor = "PT30S")
    public void sweep() {
        try {
            int sent = sweepHackathons(Instant.now());
            if (sent > 0) {
                log.info("Hackathon sweep: {} notifications sent", sent);
            }
        } catch (RuntimeException e) {
            log.error("Hackathon sweep failed: {}", e.getMessage());
        }
    }

    /** Sends whatever is due at {@code now}; returns how many notifications went out. */
    public int sweepHackathons(Instant now) {
        long time = now.toEpochMilli();
        Query eventsQuery = Query.query(Criteria.where("category").is("HACKATHON").and("status").is(EventStatus.PUBLISHED.name())
                .and("startAt").lte(Instant.ofEpochMilli(time + 2 * HOUR)).and("endAt").gt(Instant.ofEpochMilli(time - 12 * HOUR)));
        eventsQuery.fields().include("title");
        List<Event> events = mongo.find(eventsQuery, Event.class);
        if (events.isEmpty()) {
            return 0;
        }
        Map<String, Hackathon> hackathons = new HashMap<>();
        mongo.find(Query.query(Criteria.where("event").in(events.stream().map(event -> oid(event.getId())).toList())), Hackathon.class)
                .forEach(hackathon -> hackathons.put(hackathon.getEvent(), hackathon));

        int sent = 0;
        for (Event event : events) {
            Hackathon hackathon = hackathons.get(event.getId());
            if (hackathon == null) {
                continue;
            }
            try {
                sent += sweep(event, hackathon, time);
            } catch (RuntimeException e) {
                log.error("Hackathon sweep failed for event {}: {}", event.getId(), e.getMessage());
            }
        }
        return sent;
    }

    private int sweep(Event event, Hackathon hackathon, long time) {
        String link = "/events/" + event.getId() + "/hackathon";
        long selection = ms(hackathon.getSelectionDeadline());
        long repo = ms(hackathon.getRepoDeadline());
        long submission = ms(hackathon.getSubmissionDeadline());
        boolean hasProblems = !hackathon.getProblemStatements().isEmpty();
        List<Step> steps = List.of(
                new Step("REVEAL", time >= ms(hackathon.getRevealAt()) && time < selection && hasProblems,
                        () -> participantIds(event),
                        () -> "Problem statements are live: " + event.getTitle(),
                        () -> "Choose your team's problem by " + CampusTime.formatTime(hackathon.getSelectionDeadline()) + ".", link),
                new Step("SELECTION_1H", time >= selection - HOUR && time < selection && hasProblems,
                        () -> laggingMemberIds(event, "problemStatement", false),
                        () -> "Choose a problem statement by " + CampusTime.formatTime(hackathon.getSelectionDeadline()),
                        () -> "Your team hasn't picked a problem for " + event.getTitle() + " yet.", link),
                new Step("REPO_1H", time >= repo - HOUR && time < repo,
                        () -> laggingMemberIds(event, "repoSubmittedAt", false),
                        () -> "Add your code repository by " + CampusTime.formatTime(hackathon.getRepoDeadline()),
                        () -> event.getTitle() + ": teams without a repository link by then can't make a final submission.", link),
                new Step("FINAL_OPEN", time >= repo && time < submission,
                        () -> laggingMemberIds(event, "repoSubmittedAt", true),
                        () -> "Final submissions are open: " + event.getTitle(),
                        () -> "Add your live demo, video and slides by " + CampusTime.formatTime(hackathon.getSubmissionDeadline()) + ".", link),
                new Step("SUBMISSION_1H", time >= submission - HOUR && time < submission,
                        () -> laggingMemberIds(event, "submittedAt", false),
                        () -> "Submit your project by " + CampusTime.formatTime(hackathon.getSubmissionDeadline()),
                        () -> event.getTitle() + ": submissions close in under an hour. Late projects can't be judged.", link),
                new Step("JUDGING_OPEN", time >= submission && !hackathon.getJudges().isEmpty(),
                        () -> hackathon.getJudges().stream().map(HackathonJudge::getUser).toList(),
                        () -> "Judging is open: " + event.getTitle(),
                        () -> "All projects are in. Score each one on the judging panel.", link + "?tab=judging"));
        int sent = 0;
        for (Step step : steps) {
            if (!step.due() || !markNotified(hackathon, step.kind())) {
                continue;
            }
            notifications.notify(step.to().get(), Notice.builder()
                    .type(NotificationType.HACKATHON_UPDATE)
                    .title(step.title().get())
                    .message(step.message().get())
                    .link(step.link())
                    .build());
            sent++;
        }
        return sent;
    }

    /** Claims a notification kind for this hackathon; false when another run already sent it. */
    private boolean markNotified(Hackathon hackathon, String kind) {
        Query unclaimed = Query.query(Criteria.where("_id").is(oid(hackathon.getId())).and("notified").ne(kind));
        return mongo.updateFirst(unclaimed, new Update().push("notified", kind), HackathonSupport.COLLECTION).getModifiedCount() == 1;
    }

    private List<Document> registrations(Event event) {
        Query query = Query.query(Criteria.where("event").is(oid(event.getId())).and("status").is(RegistrationStatus.REGISTERED.name()));
        query.fields().include("user").include("team");
        return mongo.find(query, Document.class, "eventregistrations");
    }

    private List<String> participantIds(Event event) {
        return registrations(event).stream().map(row -> row.getObjectId("user").toHexString()).toList();
    }

    /**
     * Members of entries that haven't done a step yet ({@code field} still empty), or — with {@code done} — those that
     * have (their final submission is open).
     */
    private List<String> laggingMemberIds(Event event, String field, boolean done) {
        Query entriesQuery = Query.query(Criteria.where("event").is(oid(event.getId())));
        entriesQuery.fields().include("entryKey").include(field);
        Set<String> finished = new HashSet<>();
        mongo.find(entriesQuery, Document.class, HackathonSupport.ENTRIES).forEach(entry -> {
            if (entry.get(field) != null) {
                finished.add(entry.getString("entryKey"));
            }
        });
        List<String> users = new ArrayList<>();
        for (Document row : registrations(event)) {
            String key = row.getObjectId("team") != null ? "team:" + row.getObjectId("team").toHexString() : "user:" + row.getObjectId("user").toHexString();
            if (finished.contains(key) == done) {
                users.add(row.getObjectId("user").toHexString());
            }
        }
        return users;
    }
}
