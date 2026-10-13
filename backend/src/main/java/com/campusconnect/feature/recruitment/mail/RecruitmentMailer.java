package com.campusconnect.feature.recruitment.mail;

import com.campusconnect.core.mail.EmailContent;
import com.campusconnect.core.mail.EmailContent.Action;
import com.campusconnect.core.mail.EmailContent.Detail;
import com.campusconnect.core.mail.Recipient;
import com.campusconnect.core.notify.EmailCategory;
import com.campusconnect.core.notify.EmailRequest;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RoundMode;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.venue.Venue;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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
 * Every recruitment notification and e-mail (services/RecruitmentMailer.js), with the same titles, subjects and
 * de-duplication keys. Messages to applicants about their own application are ACCOUNT e-mails (always delivered);
 * the "applications are open" announcement uses the optional "Club recruitment" category. Messages that differ only
 * by recipient go out in one batch (one recipient lookup) instead of one call per person. Nothing here may undo the
 * change that triggered it, so failures are logged and swallowed.
 */
@Slf4j
@Service
public class RecruitmentMailer {

    private final NotificationService notifications;
    private final MongoOperations mongo;

    public RecruitmentMailer(NotificationService notifications, MongoOperations mongo) {
        this.notifications = notifications;
        this.mongo = mongo;
    }

    /** A candidate's interview time, for invitations and reminders. */
    public record Slot(Instant startAt, Instant endAt) {
    }

    public static String drivePath(RecruitmentDrive drive) {
        return "/recruitment/" + drive.getId();
    }

    static String firstName(String name) {
        return (name == null || name.isEmpty() ? "there" : name).split(" ", -1)[0];
    }

    private static String deadline(Instant date) {
        return CampusTime.formatDate(date) + ", " + CampusTime.formatTime(date);
    }

    private static String positionsLabel(RecruitmentDrive drive) {
        return drive.getPositions().stream().map(RecruitmentPosition::getTitle).collect(Collectors.joining(", "));
    }

    /** Paragraphs without the empty ones ({@code .filter(Boolean)}). */
    private static List<String> present(String... paragraphs) {
        return Stream.of(paragraphs).filter(Objects::nonNull).filter(text -> !text.isEmpty()).toList();
    }

    private void safely(String label, Runnable task) {
        try {
            task.run();
        } catch (RuntimeException e) {
            log.error("Recruitment email failed: {} ({})", label, e.getMessage());
        }
    }

    /** Personal ACCOUNT e-mails; {@code dedupeKey} names the e-mail per recipient so nobody gets it twice. */
    private void email(Collection<String> userIds, Function<Recipient, String> dedupeKey, Function<Recipient, EmailContent> compose) {
        notifications.emailUsers(userIds, EmailRequest.builder().category(EmailCategory.ACCOUNT).dedupeKey(dedupeKey).compose(compose).build());
    }

    private void notify(Collection<String> userIds, NotificationType type, String title, String message, String link) {
        notifications.notify(userIds, Notice.builder().type(type).title(title).message(message).link(link).build());
    }

    // ---------------------------------------------------------------- Drive published

    /** Students who could apply: in the club's department scope and batch filter, not already members. */
    public List<String> recruitmentAudience(RecruitmentDrive drive, Club club) {
        Criteria filter = Criteria.where("accountType").is(AccountType.STUDENT.name()).and("isActive").is(true).and("isEmailVerified").is(true);
        if (!club.isAllDepartments()) {
            filter = filter.and("departmentCode").in(club.getDepartmentCodes() == null ? List.of() : club.getDepartmentCodes());
        }
        List<String> batches = drive.getEligibility() == null ? List.of() : drive.getEligibility().getBatches();
        if (!batches.isEmpty()) {
            filter = filter.and("batchCode").in(batches);
        }
        Query members = Query.query(Criteria.where("club").is(new ObjectId(club.getId())).and("status").is(MembershipStatus.APPROVED.name()));
        members.fields().include("user");
        List<ObjectId> memberIds = mongo.find(members, Document.class, "clubmemberships").stream().map(row -> row.getObjectId("user")).filter(Objects::nonNull).toList();
        Query query = Query.query(filter.and("_id").nin(memberIds));
        query.fields().include("_id");
        return mongo.find(query, Document.class, "users").stream().map(user -> user.getObjectId("_id").toHexString()).toList();
    }

    public void sendRecruitmentLaunch(RecruitmentDrive drive, Club club, AuthUser actor) {
        safely("launch", () -> {
            List<String> audience = recruitmentAudience(drive, club);
            boolean opensLater = drive.getApplicationStart().isAfter(Instant.now());
            String title = club.getName() + " is recruiting: " + drive.getTitle();
            notifications.notify(audience, Notice.builder()
                    .type(NotificationType.RECRUITMENT_OPEN)
                    .title(title)
                    .message(opensLater
                            ? "Applications open " + CampusTime.formatDate(drive.getApplicationStart()) + " and close " + deadline(drive.getApplicationEnd()) + "."
                            : "Apply by " + deadline(drive.getApplicationEnd()) + ". Positions: " + positionsLabel(drive) + ".")
                    .link(drivePath(drive))
                    .exclude(List.of(actor.id()))
                    .build());
            String description = drive.getDescription() == null ? "" : drive.getDescription();
            EmailContent content = EmailContent.builder()
                    .subject(title)
                    .heading(club.getName() + " is looking for new members")
                    .paragraphs(present(
                            club.getName() + " has opened recruitment for " + drive.getTitle() + ".",
                            description.length() > 600 ? description.substring(0, 600) : description,
                            opensLater ? "Applications open on " + CampusTime.formatDate(drive.getApplicationStart()) + "." : "Fill in the application form — it only takes a few minutes."))
                    .details(List.of(new Detail("Positions", positionsLabel(drive)), new Detail("Apply by", deadline(drive.getApplicationEnd()))))
                    .image(club.getCoverImage() == null || club.getCoverImage().isEmpty() ? null : club.getCoverImage())
                    .action(new Action(opensLater ? "See the details" : "Apply now", drivePath(drive)))
                    .reason("You're receiving this because you can join this club.")
                    .build();
            notifications.emailUsers(audience, EmailRequest.builder()
                    .category(EmailCategory.RECRUITMENT)
                    .exclude(List.of(actor.id()))
                    .dedupeKey(recipient -> "recruitment-open:" + drive.getId() + ":" + recipient.id())
                    .compose(recipient -> content)
                    .build());
        });
    }

    // ---------------------------------------------------------------- Applications

    public void sendApplicationReceived(RecruitmentDrive drive, Club club, String applicantId, String positionTitle) {
        safely("received", () -> email(List.of(applicantId),
                recipient -> "application-received:" + drive.getId() + ":" + applicantId + ":" + positionTitle,
                recipient -> EmailContent.builder()
                        .subject("Application received: " + positionTitle + " — " + club.getName())
                        .heading("We've received your application")
                        .paragraphs(List.of(
                                "Thank you for applying to be " + positionTitle + " at " + club.getName() + " (" + drive.getTitle() + ").",
                                "The club will review applications after the deadline. You'll get an email for every step — interview invitations, round results and the final decision."))
                        .details(List.of(new Detail("Role", positionTitle), new Detail("Applications close", deadline(drive.getApplicationEnd()))))
                        .action(new Action("View your application", drivePath(drive)))
                        .build()));
    }

    // ---------------------------------------------------------------- Interviews

    static String roundWhere(RecruitmentRound round, Venue venue) {
        if (round.getMode() == RoundMode.ONLINE) {
            return "Online meeting";
        }
        if (venue == null) {
            return null;
        }
        return venue.getName() + (venue.getLocation() != null && !venue.getLocation().isEmpty() ? ", " + venue.getLocation() : "");
    }

    private static List<Detail> interviewDetails(RecruitmentPosition position, RecruitmentRound round, Slot slot, Venue venue) {
        return List.of(
                new Detail("Role", position.getTitle()),
                new Detail("Round", round.getName()),
                new Detail("When", CampusTime.formatSchedule(slot.startAt(), slot.endAt())),
                new Detail("Where", roundWhere(round, venue)),
                new Detail("Meeting link", round.getMode() == RoundMode.ONLINE ? round.getMeetingLink() : null));
    }

    private static boolean hasMeetingLink(RecruitmentRound round) {
        return round.getMeetingLink() != null && !round.getMeetingLink().isEmpty();
    }

    private static Action interviewAction(RecruitmentDrive drive, RecruitmentRound round) {
        return round.getMode() == RoundMode.ONLINE && hasMeetingLink(round)
                ? new Action("Join the meeting", round.getMeetingLink())
                : new Action("View details", drivePath(drive));
    }

    /** Invitations (or, {@code changed}, updated times) for each candidate's own slot: user id to slot. */
    public void sendInterviewInvites(RecruitmentDrive drive, Club club, RecruitmentPosition position, RecruitmentRound round, Map<String, Slot> slots, Venue venue, boolean changed) {
        if (slots.isEmpty()) {
            return;
        }
        safely("invite", () -> {
            String kind = changed ? "changed" : "invite";
            String title = (changed ? "Updated time: " : "You're invited: ") + round.getName() + " (" + position.getTitle() + ") — " + club.getName();
            String where = roundWhere(round, venue);
            slots.forEach((userId, slot) -> notify(List.of(userId), NotificationType.INTERVIEW_SCHEDULED, title,
                    CampusTime.formatSchedule(slot.startAt(), slot.endAt()) + (where != null ? " · " + where : ""), drivePath(drive)));
            String roundName = round.getName().toLowerCase(Locale.ROOT);
            email(slots.keySet(),
                    recipient -> "interview-" + kind + ":" + round.getId() + ":" + recipient.id() + ":" + slots.get(recipient.id()).startAt().toEpochMilli(),
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading(changed ? "Your interview time has changed" : round.getName() + ": you're invited")
                            .paragraphs(present(
                                    changed
                                            ? club.getName() + " has moved your " + roundName + " for " + position.getTitle() + ". Here are the new details."
                                            : "Good news, " + firstName(recipient.name()) + " — " + club.getName() + " would like to meet you for the " + roundName + " for " + position.getTitle() + ".",
                                    round.getInstructions(),
                                    round.getMode() == RoundMode.ONLINE
                                            ? "Join a couple of minutes early from a quiet place with a working camera and microphone."
                                            : "Please arrive 5 minutes early and carry your college ID card.",
                                    "We'll remind you 1 hour and 10 minutes before it starts."))
                            .details(interviewDetails(position, round, slots.get(recipient.id()), venue))
                            .action(interviewAction(drive, round))
                            .build());
        });
    }

    /** Reminder 60 or 10 minutes before a candidate's interview. */
    public void sendInterviewReminder(RecruitmentDrive drive, Club club, RecruitmentPosition position, RecruitmentRound round, Slot slot, String userId, Venue venue, int minutes) {
        safely("reminder", () -> {
            String title = "Starts in " + minutes + " minutes: " + round.getName() + " (" + position.getTitle() + ") — " + club.getName();
            String where = roundWhere(round, venue);
            boolean online = round.getMode() == RoundMode.ONLINE;
            notify(List.of(userId), NotificationType.INTERVIEW_REMINDER, title, CampusTime.formatTime(slot.startAt()) + (where != null ? " · " + where : ""),
                    online && hasMeetingLink(round) ? round.getMeetingLink() : drivePath(drive));
            String roundName = round.getName().toLowerCase(Locale.ROOT);
            email(List.of(userId),
                    recipient -> "interview-reminder:" + round.getId() + ":" + recipient.id() + ":" + minutes,
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading("Your " + roundName + " starts in " + minutes + " minutes")
                            .paragraphs(List.of(minutes >= 60
                                    ? "A quick reminder about your " + roundName + " with " + club.getName() + " at " + CampusTime.formatTime(slot.startAt()) + ". Take a moment to prepare — you've got this!"
                                    : online ? "It's almost time. Open the meeting link now so you're ready when it starts." : "It's almost time. Please head to the venue now."))
                            .details(interviewDetails(position, round, slot, venue))
                            .action(interviewAction(drive, round))
                            .build());
        });
    }

    // ---------------------------------------------------------------- Results

    /** A round's results: qualified candidates are congratulated, eliminated ones thanked. */
    public void sendRoundResults(RecruitmentDrive drive, Club club, RecruitmentPosition position, RecruitmentRound round, List<String> qualified, List<String> eliminated) {
        sendRoundResult(drive, club, position, round, qualified, true);
        sendRoundResult(drive, club, position, round, eliminated, false);
    }

    private void sendRoundResult(RecruitmentDrive drive, Club club, RecruitmentPosition position, RecruitmentRound round, List<String> userIds, boolean qualified) {
        if (userIds.isEmpty()) {
            return;
        }
        safely("round result", () -> {
            String title = qualified
                    ? "Congratulations! You cleared " + round.getName() + " (" + position.getTitle() + ") — " + club.getName()
                    : club.getName() + " recruitment: " + round.getName() + " result (" + position.getTitle() + ")";
            notify(userIds, NotificationType.APPLICATION_UPDATE, title,
                    qualified ? "You've qualified for the next stage. Details will follow." : "Thank you for taking part — keep going!", drivePath(drive));
            String roundName = round.getName().toLowerCase(Locale.ROOT);
            email(userIds,
                    recipient -> "round-result:" + round.getId() + ":" + recipient.id(),
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading(qualified ? "You've cleared " + round.getName() + "! 🎉" : "Thank you for your effort")
                            .paragraphs(qualified
                                    ? List.of(
                                            "Congratulations, " + firstName(recipient.name()) + "! You've qualified in the " + roundName + " for " + position.getTitle() + ".",
                                            "The club will share the next step soon — keep an eye on your email.",
                                            "Well done, and all the best for what's next!")
                                    : List.of(
                                            "Thank you for applying to be " + position.getTitle() + " at " + club.getName() + ", " + firstName(recipient.name())
                                                    + ". After careful consideration, you haven't been selected to move beyond the " + roundName + " this time.",
                                            "This was a competitive round, and the effort you put in genuinely counts. Every interview is practice for the next one — keep building your skills, and do apply again when the club recruits next.",
                                            "You're always welcome at " + club.getName() + "'s events. We hope to see you there!"))
                            .action(new Action("View your application", drivePath(drive)))
                            .build());
        });
    }

    /** Offers to join in one role (all made at once, so with one deadline); students answer on the drive page. */
    public void sendOffers(RecruitmentDrive drive, Club club, RecruitmentPosition position, List<String> userIds, Instant expiresAt) {
        if (userIds.isEmpty()) {
            return;
        }
        safely("offer", () -> {
            String title = "Offer: " + position.getTitle() + " at " + club.getName() + " 🎉";
            String link = drivePath(drive) + "?offer=1";
            notify(userIds, NotificationType.RECRUITMENT_OFFER, title, "Accept by " + deadline(expiresAt) + ".", link);
            email(userIds,
                    recipient -> "offer:" + drive.getId() + ":" + recipient.id() + ":" + position.getId() + ":" + expiresAt.toEpochMilli(),
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading("You've been offered " + position.getTitle() + "!")
                            .paragraphs(List.of(
                                    "Congratulations, " + firstName(recipient.name()) + "! After the selection rounds, " + club.getName() + " would love you to join as " + position.getTitle() + ".",
                                    "Please accept or decline by " + deadline(expiresAt) + ". If you also applied for other roles, you can join in only one: accepting this offer closes your other applications."))
                            .details(List.of(new Detail("Role", position.getTitle()), new Detail("Answer by", deadline(expiresAt))))
                            .action(new Action("Accept or decline", link))
                            .build());
        });
    }

    public void sendReserve(RecruitmentDrive drive, Club club, RecruitmentPosition position, List<String> userIds) {
        if (userIds.isEmpty()) {
            return;
        }
        safely("reserve", () -> {
            String title = "You're on the reserve list: " + position.getTitle() + " — " + club.getName();
            notify(userIds, NotificationType.APPLICATION_UPDATE, title, "If a seat frees up, you'll get an offer.", drivePath(drive));
            email(userIds,
                    recipient -> "reserve:" + drive.getId() + ":" + recipient.id() + ":" + position.getId(),
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading("You're on the reserve list")
                            .paragraphs(List.of(
                                    "Well done, " + firstName(recipient.name()) + " — you made it to the final stage for " + position.getTitle() + ". The seats have been offered for now, and you're next in line.",
                                    "If a seat frees up, you'll get an offer straight away by email."))
                            .action(new Action("View your application", drivePath(drive)))
                            .build());
        });
    }

    public void sendNotSelected(RecruitmentDrive drive, Club club, List<String> userIds, String positionTitle) {
        if (userIds.isEmpty()) {
            return;
        }
        safely("not selected", () -> {
            String title = club.getName() + " recruitment: " + positionTitle;
            notify(userIds, NotificationType.APPLICATION_UPDATE, title, "Thank you for applying — see the message from the club.", drivePath(drive));
            email(userIds,
                    recipient -> "not-selected:" + drive.getId() + ":" + recipient.id() + ":" + positionTitle,
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading("Thank you for applying")
                            .paragraphs(List.of(
                                    "Thank you for your time and effort throughout " + club.getName() + "'s " + drive.getTitle() + ", " + firstName(recipient.name()) + ".",
                                    "We couldn't offer you the " + positionTitle + " role this time — it was very close. Please don't be discouraged: keep learning, and apply again when the club recruits next.",
                                    "You're always welcome at " + club.getName() + "'s events."))
                            .action(new Action("View your application", drivePath(drive)))
                            .build());
        });
    }

    /** The student accepted an offer: they're a member now, and their other applications were closed. */
    public void sendWelcome(RecruitmentDrive drive, Club club, String userId, String positionTitle, List<String> closedTitles) {
        safely("welcome", () -> {
            String title = "Welcome to " + club.getName() + "! 🎉";
            String clubPath = "/clubs/" + club.getId();
            notify(List.of(userId), NotificationType.APPLICATION_UPDATE, title, "You've joined as " + positionTitle + ".", clubPath);
            email(List.of(userId),
                    recipient -> "welcome:" + drive.getId() + ":" + recipient.id(),
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading("You're in — welcome to " + club.getName() + "!")
                            .paragraphs(present(
                                    "Congratulations, " + firstName(recipient.name()) + "! You've accepted the offer and joined " + club.getName() + " as " + positionTitle + ".",
                                    "You'll see the club in your dashboard, get member updates and can help run its events. The team will reach out about your first meeting.",
                                    closedTitles.isEmpty() ? null : "As you can hold one role, your applications for " + String.join(", ", closedTitles) + " have been closed.",
                                    "Welcome aboard!"))
                            .details(List.of(new Detail("Role", positionTitle)))
                            .action(new Action("Open " + club.getName(), clubPath))
                            .build());
        });
    }

    public void sendOfferExpired(RecruitmentDrive drive, Club club, RecruitmentPosition position, String userId) {
        safely("offer expired", () -> {
            String title = "Your " + position.getTitle() + " offer has expired — " + club.getName();
            notify(List.of(userId), NotificationType.APPLICATION_UPDATE, title, "The deadline passed without an answer.", drivePath(drive));
            email(List.of(userId),
                    recipient -> "offer-expired:" + drive.getId() + ":" + recipient.id() + ":" + position.getId(),
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading("Your offer has expired")
                            .paragraphs(List.of(
                                    "The offer to join " + club.getName() + " as " + position.getTitle() + " wasn't answered in time, so the seat may go to someone else.",
                                    "If you think this is a mistake, contact the club."))
                            .action(new Action("View your application", drivePath(drive)))
                            .build());
        });
    }

    public void sendDriveCancelled(RecruitmentDrive drive, Club club, List<String> userIds, String reason) {
        safely("cancelled", () -> {
            boolean hasReason = reason != null && !reason.isEmpty();
            String title = club.getName() + " recruitment cancelled";
            notify(userIds, NotificationType.APPLICATION_UPDATE, title, hasReason ? reason : "The club cancelled this recruitment drive.", drivePath(drive));
            email(userIds,
                    recipient -> "recruitment-cancelled:" + drive.getId() + ":" + recipient.id(),
                    recipient -> EmailContent.builder()
                            .subject(title)
                            .heading(drive.getTitle() + " has been cancelled")
                            .paragraphs(present(
                                    club.getName() + " has cancelled this recruitment drive, so your application is closed.",
                                    hasReason ? "Reason: " + reason : null,
                                    "Thank you for your interest — we'll let you know when the club recruits again."))
                            .build());
        });
    }
}
