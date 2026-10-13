package com.campusconnect.feature.audience;

import com.campusconnect.core.mail.EmailContent;
import com.campusconnect.core.mail.EmailContent.Detail;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.EmailRequest;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.event.Event;
import com.campusconnect.domain.event.RegistrationStatus;
import com.campusconnect.domain.result.EventResult;
import com.campusconnect.domain.result.ResultAward;
import com.campusconnect.domain.result.ResultEntry;
import com.campusconnect.domain.result.ResultRound;
import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.venue.Venue;
import com.campusconnect.feature.subscription.SubscriptionService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import lombok.extern.slf4j.Slf4j;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Campaign e-mails for the moments people care about (services/CampusMailer.js). Each person gets at most one
 * copy of each (dedupe keys), in the most relevant wording, and only if their e-mail settings allow it. Failures
 * are logged and never undo the publish that triggered them.
 */
@Slf4j
@Service
public class CampusMailer {

    private final MongoOperations mongo;
    private final NotificationService notifications;
    private final SubscriptionService subscriptions;

    public CampusMailer(MongoOperations mongo, NotificationService notifications, SubscriptionService subscriptions) {
        this.mongo = mongo;
        this.notifications = notifications;
        this.subscriptions = subscriptions;
    }

    static String audienceLabel(Event event) {
        List<String> departments = event.getEligibility() == null ? List.of() : event.getEligibility().getDepartments();
        List<String> batches = event.getEligibility() == null ? List.of() : event.getEligibility().getBatches();
        if (departments.isEmpty() && batches.isEmpty()) {
            return "All students";
        }
        return Stream.of(departments.isEmpty() ? null : String.join(", ", departments),
                        batches.isEmpty() ? null : "batch " + batches.stream().map(code -> "20" + code).collect(Collectors.joining(", ")))
                .filter(Objects::nonNull).collect(Collectors.joining(" · "));
    }

    /** Students who could register for the event (same rule as RegistrationService.eligibilityProblem). */
    public List<String> eligibleStudentIds(Event event) {
        Criteria filter = Criteria.where("accountType").is(AccountType.STUDENT.name()).and("isActive").is(true).and("isEmailVerified").is(true);
        if (event.getEligibility() != null && !event.getEligibility().getDepartments().isEmpty()) {
            filter = filter.and("departmentCode").in(event.getEligibility().getDepartments());
        }
        if (event.getEligibility() != null && !event.getEligibility().getBatches().isEmpty()) {
            filter = filter.and("batchCode").in(event.getEligibility().getBatches());
        }
        Query query = Query.query(filter);
        query.fields().include("_id");
        return mongo.find(query, Document.class, "users").stream().map(user -> user.getObjectId("_id").toHexString()).toList();
    }

    private List<Detail> eventDetails(Event event, Venue venue) {
        return List.of(
                new Detail("When", CampusTime.formatSchedule(event.getStartAt(), event.getEndAt())),
                new Detail("Where", venue != null ? venue.getName() + ", " + venue.getLocation() : null),
                new Detail("Register by", event.getRegistrationEnd() == null ? null : CampusTime.formatDate(event.getRegistrationEnd()) + ", " + CampusTime.formatTime(event.getRegistrationEnd())),
                new Detail("Open to", audienceLabel(event)),
                new Detail("Seats", event.getMaxParticipants() != null && event.getMaxParticipants() > 0 ? event.getMaxParticipants() + " places" : null));
    }

    private static EmailContent.ClubLink link(Club club) {
        return new EmailContent.ClubLink(club.getId(), club.getName());
    }

    private void safely(String name, Runnable send) {
        try {
            send.run();
        } catch (RuntimeException e) {
            log.error("Failed to queue {} emails: {}", name, e.getMessage());
        }
    }

    /** New event: the club's followers hear it from the club; other eligible students get it as a recommendation. */
    public void sendEventLaunchEmails(Event event, Club club, AuthUser actor) {
        safely("event launch", () -> {
            Venue venue = event.getVenue() == null ? null : mongo.findById(event.getVenue(), Venue.class);
            List<String> followers = subscriptions.clubFollowerIds(club.getId());
            // Anyone who muted this club is not re-targeted as a "recommendation".
            Set<String> skip = new HashSet<>(followers);
            skip.addAll(subscriptions.optedOutIds(club.getId()));
            List<String> eligible = eligibleStudentIds(event).stream().filter(id -> !skip.contains(id)).toList();

            List<Detail> details = eventDetails(event, venue);
            EmailContent.Action action = new EmailContent.Action("View event & register", "/events/" + event.getId());

            int toFollowers = notifications.emailUsers(followers, EmailRequest.builder()
                    .category(EmailCategory.CLUB_UPDATES)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "event-published:" + event.getId() + ":" + user.id())
                    .compose(user -> EmailContent.builder()
                            .heading(event.getTitle()).image(event.getPoster()).details(details).action(action)
                            .subject(club.getName() + " just announced: " + event.getTitle())
                            .paragraphs(nonNull(event.getShortDescription()))
                            .reason("You're receiving this because notifications are on for " + club.getName() + ".")
                            .club(link(club))
                            .build())
                    .build());

            int toEligible = notifications.emailUsers(eligible, EmailRequest.builder()
                    .category(EmailCategory.EVENT_RECOMMENDATIONS)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "event-published:" + event.getId() + ":" + user.id())
                    .compose(user -> EmailContent.builder()
                            .heading(event.getTitle()).image(event.getPoster()).details(details).action(action)
                            .subject("New event you can join: " + event.getTitle())
                            .paragraphs(nonNull(club.getName() + " is hosting a new event that's open to you.", event.getShortDescription()))
                            .reason("You're receiving this because this event is open to your department and batch.")
                            .build())
                    .build());

            log.info("Event launch emails queued for {}: {} followers, {} recommendations", event.getId(), toFollowers, toEligible);
        });
    }

    private List<String> registeredUserIds(String eventId) {
        Query query = Query.query(Criteria.where("event").is(new ObjectId(eventId)).and("status").is(RegistrationStatus.REGISTERED.name()));
        query.fields().include("user");
        return mongo.find(query, Document.class, "eventregistrations").stream().map(row -> row.getObjectId("user").toHexString()).toList();
    }

    private static String winnerLine(ResultAward award) {
        return Stream.of(award.getTitle(), award.getTeamName() != null && !award.getTeamName().isEmpty() ? award.getTeamName() : award.getRecipientName())
                .filter(value -> value != null && !value.isEmpty()).collect(Collectors.joining(": "));
    }

    /** Results: winners get a personal congratulations, participants the results, members the club news. */
    public void sendResultEmails(Event event, EventResult result, Club club, AuthUser actor) {
        safely("result", () -> {
            List<String> participants = registeredUserIds(event.getId());
            List<ResultAward> awards = result.getAwards().stream()
                    .sorted(Comparator.comparingInt(award -> award.getPosition() == null || award.getPosition() == 0 ? 99 : award.getPosition()))
                    .toList();
            Map<String, ResultAward> awardByWinner = new LinkedHashMap<>();
            awards.forEach(award -> {
                if (award.getRecipientUser() != null) {
                    awardByWinner.putIfAbsent(award.getRecipientUser(), award);
                }
            });
            List<String> winners = new ArrayList<>(awardByWinner.keySet());
            Set<String> notified = new HashSet<>(winners);
            List<String> others = participants.stream().filter(id -> !notified.contains(id)).toList();
            notified.addAll(others);
            List<String> members = subscriptions.clubFollowerIds(club.getId(), true).stream().filter(id -> !notified.contains(id)).toList();

            List<Detail> details = awards.stream().limit(5)
                    .map(award -> new Detail(award.getPosition() != null && award.getPosition() > 0 ? "#" + award.getPosition() : "Award", winnerLine(award))).toList();
            EmailContent.Action action = new EmailContent.Action("See full results", "/results/" + event.getId());
            List<String> summary = result.getSummary() != null && !result.getSummary().isEmpty()
                    ? List.of(result.getSummary().length() > 600 ? result.getSummary().substring(0, 600) : result.getSummary()) : List.of();
            String heading = "Results: " + event.getTitle();

            notifications.emailUsers(winners, EmailRequest.builder()
                    .category(EmailCategory.EVENT_ACTIVITY)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "results:" + event.getId() + ":" + user.id())
                    .compose(user -> {
                        ResultAward award = awardByWinner.get(user.id());
                        List<String> paragraphs = new ArrayList<>();
                        paragraphs.add(club.getName() + " has published the results of " + event.getTitle() + ", and you're among the winners.");
                        if (award.getPrize() != null && !award.getPrize().isEmpty()) {
                            paragraphs.add("Prize: " + award.getPrize());
                        }
                        paragraphs.addAll(summary);
                        return EmailContent.builder()
                                .details(details).action(action)
                                .subject("Congratulations! " + award.getTitle() + " at " + event.getTitle())
                                .heading("Congratulations, you won " + award.getTitle() + "!")
                                .paragraphs(paragraphs)
                                .reason("You're receiving this because you took part in " + event.getTitle() + ".")
                                .build();
                    })
                    .build());

            notifications.emailUsers(others, EmailRequest.builder()
                    .category(EmailCategory.EVENT_ACTIVITY)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "results:" + event.getId() + ":" + user.id())
                    .compose(user -> EmailContent.builder()
                            .heading(heading).details(details).action(action)
                            .subject("Results are out: " + event.getTitle())
                            .paragraphs(concat(List.of("Thanks for taking part! " + club.getName() + " has published the results."), summary))
                            .reason("You're receiving this because you registered for " + event.getTitle() + ".")
                            .build())
                    .build());

            notifications.emailUsers(members, EmailRequest.builder()
                    .category(EmailCategory.CLUB_UPDATES)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "results:" + event.getId() + ":" + user.id())
                    .compose(user -> EmailContent.builder()
                            .heading(heading).details(details).action(action)
                            .subject(club.getName() + " results: " + event.getTitle())
                            .paragraphs(concat(List.of("The results of " + event.getTitle() + " are now published."), summary))
                            .reason("You're receiving this because you're a member of " + club.getName() + ".")
                            .club(link(club))
                            .build())
                    .build());
        });
    }

    private static String entryName(ResultEntry entry) {
        if (entry.getTeamName() != null && !entry.getTeamName().isEmpty()) {
            return entry.getTeamName();
        }
        return entry.getRecipientName() != null && !entry.getRecipientName().isEmpty() ? entry.getRecipientName() : "—";
    }

    /** A round's results go to everyone registered; people named as qualifying get a personal "you're through". */
    public void sendRoundResultEmails(Event event, ResultRound round, Club club, AuthUser actor) {
        safely("round result", () -> {
            List<String> participants = registeredUserIds(event.getId());
            Set<String> qualifiedUsers = round.getEntries().stream()
                    .filter(entry -> Boolean.TRUE.equals(entry.getQualified()) && entry.getRecipientUser() != null)
                    .map(ResultEntry::getRecipientUser).collect(Collectors.toSet());
            long qualifiedCount = round.getEntries().stream().filter(entry -> Boolean.TRUE.equals(entry.getQualified())).count();

            List<Detail> details = round.getEntries().stream().limit(5)
                    .map(entry -> new Detail(entry.getRank() != null && entry.getRank() > 0 ? "#" + entry.getRank() : "•",
                            Stream.of(entryName(entry), entry.getScore()).filter(value -> value != null && !value.isEmpty()).collect(Collectors.joining(" · "))))
                    .toList();
            EmailContent.Action action = new EmailContent.Action("See the standings", "/results/" + event.getId());
            String heading = round.getName() + ": " + event.getTitle();
            String reason = "You're receiving this because you registered for " + event.getTitle() + ".";

            notifications.emailUsers(participants.stream().filter(qualifiedUsers::contains).toList(), EmailRequest.builder()
                    .category(EmailCategory.EVENT_ACTIVITY)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "round:" + round.getId() + ":" + user.id())
                    .compose(user -> EmailContent.builder()
                            .heading(heading).details(details).action(action).reason(reason)
                            .subject("You're through! " + round.getName() + " results for " + event.getTitle())
                            .paragraphs(nonNull("Great work — you've qualified from " + round.getName() + ". " + club.getName() + " will share what's next.", blankToNull(round.getDescription())))
                            .build())
                    .build());

            notifications.emailUsers(participants.stream().filter(id -> !qualifiedUsers.contains(id)).toList(), EmailRequest.builder()
                    .category(EmailCategory.EVENT_ACTIVITY)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "round:" + round.getId() + ":" + user.id())
                    .compose(user -> EmailContent.builder()
                            .heading(heading).details(details).action(action).reason(reason)
                            .subject(round.getName() + " results are out: " + event.getTitle())
                            .paragraphs(nonNull(club.getName() + " has published the results of " + round.getName() + ".",
                                    qualifiedCount > 0 ? qualifiedCount + " " + (qualifiedCount == 1 ? "entry goes" : "entries go") + " through to the next round." : null,
                                    blankToNull(round.getDescription())))
                            .build())
                    .build());
        });
    }

    /**
     * Announcements to the club's followers (members only for members-only posts), or to {@code recipients} chosen
     * by {@link AudienceService}; {@code audience} words the footer.
     */
    public void sendAnnouncementEmails(FeedPost post, Club club, AuthUser actor, String audience, List<String> recipients, String link) {
        safely("announcement", () -> {
            List<String> followers = recipients != null ? recipients : subscriptions.clubFollowerIds(club.getId(), "MEMBERS".equals(audience));
            List<String> paragraphs = Arrays.stream(String.valueOf(post.getBody() == null ? "" : post.getBody()).split("\\n{2,}"))
                    .map(String::trim).filter(paragraph -> !paragraph.isEmpty()).limit(8).toList();
            String reason = switch (audience == null ? "EVERYONE" : audience) {
                case "MEMBERS" -> "You're receiving this members-only update because you're in " + club.getName() + ".";
                case "CUSTOM" -> club.getName() + " sent this to you directly.";
                default -> "You're receiving this because notifications are on for " + club.getName() + ".";
            };
            notifications.emailUsers(followers, EmailRequest.builder()
                    .category(EmailCategory.CLUB_UPDATES)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(user -> "announcement:" + post.getId() + ":" + user.id())
                    .compose(user -> EmailContent.builder()
                            .subject(club.getName() + ": " + post.getTitle())
                            .heading(post.getTitle())
                            .paragraphs(paragraphs)
                            .image(post.getImage())
                            .action(new EmailContent.Action("Open in CampusConnect", link))
                            .reason(reason)
                            .club(link(club))
                            .build())
                    .build());
        });
    }

    private static List<String> nonNull(String... values) {
        return Arrays.stream(values).filter(value -> value != null && !value.isEmpty()).toList();
    }

    private static String blankToNull(String value) {
        return value == null || value.isEmpty() ? null : value;
    }

    private static List<String> concat(List<String> first, List<String> second) {
        List<String> all = new ArrayList<>(first);
        all.addAll(second);
        return all;
    }
}
