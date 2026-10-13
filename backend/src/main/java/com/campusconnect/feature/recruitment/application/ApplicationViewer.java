package com.campusconnect.feature.recruitment.application;

import static com.campusconnect.feature.recruitment.drive.DrivePhases.ACTIVE_APPLICATION;
import static com.campusconnect.feature.recruitment.drive.DrivePhases.OPEN_APPLICATION;

import com.campusconnect.core.media.MediaStores;
import com.campusconnect.domain.recruitment.AnswerFile;
import com.campusconnect.domain.recruitment.ApplicationAnswer;
import com.campusconnect.domain.recruitment.ApplicationStatus;
import com.campusconnect.domain.recruitment.InterviewSlot;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPage;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentQuestion;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RoundMode;
import com.campusconnect.domain.recruitment.RoundResult;
import com.campusconnect.domain.recruitment.RoundStatus;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import com.campusconnect.feature.recruitment.drive.Drives;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.AnswerPage;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.AnswerView;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.ApplicantRound;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.FileView;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.MyApplication;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.Place;
import com.campusconnect.feature.recruitment.dto.ApplicationViews.SlotTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Builds the applicant's views of applications (ApplicationService.js answerPages, roundsFor, myApplicationView). */
@Component
public class ApplicationViewer {

    private final MediaStores media;

    public ApplicationViewer(MediaStores media) {
        this.media = media;
    }

    /** A file answer with a viewer link; documents get a signed link that expires after an hour. */
    public FileView fileView(AnswerFile file) {
        return file == null ? null : new FileView(file.getName(), file.getFormat(), file.getBytes(), file.getKind(), media.recruitment().mediaUrls(file).url());
    }

    /** Answers grouped by the role form's pages. */
    public List<AnswerPage> answerPages(RecruitmentPosition position, RecruitmentApplication application) {
        List<AnswerPage> pages = new ArrayList<>();
        for (RecruitmentPage page : position.getForm().getPages()) {
            List<AnswerView> answers = new ArrayList<>();
            for (RecruitmentQuestion question : page.getQuestions()) {
                ApplicationAnswer answer = application.getAnswers().stream().filter(item -> question.getId().equals(item.getQuestion())).findFirst().orElse(null);
                answers.add(new AnswerView(question.getId(), question.getLabel(), question.getType(),
                        answer == null || answer.getText() == null ? "" : answer.getText(),
                        answer == null || answer.getChoices() == null ? List.of() : answer.getChoices(),
                        answer == null ? null : fileView(answer.getFile())));
            }
            pages.add(new AnswerPage(page.getId(), page.getTitle(), answers));
        }
        return pages;
    }

    static RoundResult resultOf(RecruitmentApplication application, RecruitmentRound round) {
        return application.getRoundResults().stream().filter(result -> round.getId().equals(result.getRound())).findFirst().orElse(null);
    }

    static InterviewSlot slotOf(RecruitmentApplication application, RecruitmentRound round) {
        return application.getSlots().stream().filter(slot -> round.getId().equals(slot.getRound())).findFirst().orElse(null);
    }

    /**
     * What the applicant sees of the rounds: the ones they have a result for, plus the round in progress.
     *
     * @param venues the rounds' venues by id ({@code name}, {@code location})
     */
    public List<ApplicantRound> roundsFor(RecruitmentPosition position, RecruitmentApplication application, Map<String, Map<String, Object>> venues) {
        boolean active = ACTIVE_APPLICATION.contains(application.getStatus());
        List<RecruitmentRound> rounds = position.getRounds();
        RecruitmentRound current = rounds.isEmpty() ? null : rounds.get(rounds.size() - 1);
        List<ApplicantRound> result = new ArrayList<>();
        for (RecruitmentRound round : rounds) {
            RoundResult outcome = resultOf(application, round);
            boolean inProgress = active && current != null && round.getId().equals(current.getId()) && round.getStatus() != RoundStatus.RESULTS_PUBLISHED;
            if (outcome == null && !inProgress) {
                continue;
            }
            InterviewSlot slot = slotOf(application, round);
            boolean scheduled = round.getStatus() != RoundStatus.DRAFT && round.getMode() != RoundMode.SCREENING;
            Map<String, Object> venue = scheduled && round.getVenue() != null ? venues.get(round.getVenue()) : null;
            result.add(new ApplicantRound(
                    round.getId(),
                    round.getName(),
                    round.getMode(),
                    round.getStatus(),
                    outcome == null ? null : outcome.getOutcome(),
                    scheduled && slot != null ? new SlotTime(slot.getStartAt(), slot.getEndAt()) : null,
                    venue == null ? null : new Place((String) venue.get("name"), (String) venue.get("location")),
                    scheduled && outcome == null ? round.getMeetingLink() : null,
                    scheduled ? round.getInstructions() : ""));
        }
        return result;
    }

    public MyApplication mine(RecruitmentDrive drive, RecruitmentApplication application, Map<String, Map<String, Object>> venues, Instant now) {
        RecruitmentPosition position = Drives.positionOrNull(drive, application.getPosition());
        ApplicationStatus status = application.getStatus();
        return new MyApplication(
                application.getId(),
                application.getPosition(),
                position == null ? "Role" : Objects.requireNonNullElse(position.getTitle(), "Role"),
                status,
                position == null ? List.of() : answerPages(position, application),
                position == null ? List.of() : roundsFor(position, application, venues),
                application.getOfferedAt(),
                application.getOfferExpiresAt(),
                application.getRespondedAt(),
                application.getClosedReason(),
                application.getCreatedAt(),
                application.getUpdatedAt(),
                DrivePhases.applicationsOpen(drive, now) && status == ApplicationStatus.APPLIED,
                OPEN_APPLICATION.contains(status) && status != ApplicationStatus.OFFERED);
    }
}
