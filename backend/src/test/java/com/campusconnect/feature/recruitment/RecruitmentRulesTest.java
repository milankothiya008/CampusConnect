package com.campusconnect.feature.recruitment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.error.AppException;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RecruitmentDrive;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentRound;
import com.campusconnect.domain.recruitment.RecruitmentStatus;
import com.campusconnect.domain.recruitment.RoundTiming;
import com.campusconnect.feature.recruitment.drive.DriveForms;
import com.campusconnect.feature.recruitment.drive.DrivePhases;
import com.campusconnect.feature.recruitment.dto.RecruitmentRequests;
import com.campusconnect.feature.recruitment.reminder.RecruitmentReminders;
import com.campusconnect.feature.recruitment.selection.SlotPlanner;
import com.campusconnect.feature.recruitment.support.IsoTimes;
import com.campusconnect.feature.recruitment.support.Js;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Pure rules of the recruitment port: dates and coercions as JavaScript read them, phases, slot planning, reminders, forms. */
class RecruitmentRulesTest {

    @Nested
    class DatesAndCoercions {

        @Test
        void acceptsTheIsoStringsTheOriginalValidatorAccepted() {
            assertThat(IsoTimes.isIso8601("2026-10-17T15:00:00+05:30")).isTrue();
            assertThat(IsoTimes.isIso8601("2026-10-17T09:30:00.000Z")).isTrue();
            assertThat(IsoTimes.isIso8601("2026-10-17")).isTrue();
            assertThat(IsoTimes.isIso8601("2026-10-17 15:00")).isTrue();
            assertThat(IsoTimes.isIso8601("next week")).isFalse();
            assertThat(IsoTimes.isIso8601("")).isFalse();
            assertThat(IsoTimes.isIso8601("2026-13-01")).isFalse();
        }

        @Test
        void readsTimesLikeNewDate() {
            assertThat(IsoTimes.parse("2026-10-17T15:00:00+05:30")).isEqualTo(Instant.parse("2026-10-17T09:30:00Z"));
            assertThat(IsoTimes.parse("2026-10-17T15:00:00+0530")).isEqualTo(Instant.parse("2026-10-17T09:30:00Z"));
            assertThat(IsoTimes.parse("2026-10-17T09:30:00.123456Z")).isEqualTo(Instant.parse("2026-10-17T09:30:00.123Z"));
            assertThat(IsoTimes.parse("2026-10-17")).isEqualTo(Instant.parse("2026-10-17T00:00:00Z"));
            assertThat(IsoTimes.parse("2026-10-17 09:30")).isEqualTo(Instant.parse("2026-10-17T09:30:00Z"));
            assertThat(IsoTimes.parse("soon")).isNull();
            assertThat(IsoTimes.toInstant(null)).isEqualTo(Instant.EPOCH);
            assertThat(IsoTimes.toInstant(86_400_000L)).isEqualTo(Instant.parse("1970-01-02T00:00:00Z"));
        }

        @Test
        void coercesJsonValuesLikeJavaScript() {
            assertThat(Js.string(3.0)).isEqualTo("3");
            assertThat(Js.string(List.of("a", "b"))).isEqualTo("a,b");
            assertThat(Js.string(Map.of())).isEqualTo("[object Object]");
            assertThat(Js.clean("  Design lead \n", 120)).isEqualTo("Design lead");
            assertThat(Js.number("  12 ")).isEqualTo(12);
            assertThat(Js.number("")).isZero();
            assertThat(Js.number("12px")).isNaN();
            assertThat(Js.number(true)).isEqualTo(1);
            assertThat(Js.truthy(0)).isFalse();
            assertThat(Js.truthy("")).isFalse();
            assertThat(Js.truthy(List.of())).isTrue();
            assertThat(Js.isInt("020", 5, 240)).isTrue();
            assertThat(Js.isInt(20.5, 5, 240)).isFalse();
            assertThat(RecruitmentRequests.slotMinutesValid(0)).isTrue();
            assertThat(RecruitmentRequests.slotMinutesValid(3)).isFalse();
            assertThat(RecruitmentRequests.offerDaysValid(15)).isFalse();
        }
    }

    @Nested
    class PhasesAndStages {

        RecruitmentDrive drive(RecruitmentStatus status, Instant start, Instant end) {
            RecruitmentDrive drive = new RecruitmentDrive();
            drive.setStatus(status);
            drive.setApplicationStart(start);
            drive.setApplicationEnd(end);
            drive.getPositions().add(new RecruitmentPosition());
            return drive;
        }

        @Test
        void publishedDrivesMoveFromUpcomingToOpenToClosedToRounds() {
            Instant now = Instant.parse("2026-10-14T10:00:00Z");
            assertThat(DrivePhases.phaseOf(drive(RecruitmentStatus.DRAFT, now, now), now)).isEqualTo("DRAFT");
            assertThat(DrivePhases.phaseOf(drive(RecruitmentStatus.PUBLISHED, now.plusSeconds(60), now.plusSeconds(600)), now)).isEqualTo("UPCOMING");
            RecruitmentDrive open = drive(RecruitmentStatus.PUBLISHED, now.minusSeconds(60), now.plusSeconds(600));
            assertThat(DrivePhases.phaseOf(open, now)).isEqualTo("OPEN");
            assertThat(DrivePhases.stageOf(open, open.getPositions().get(0), now)).isEqualTo("APPLICATIONS");
            open.setClosedAt(now);
            assertThat(DrivePhases.phaseOf(open, now)).isEqualTo("CLOSED");
            open.getPositions().get(0).getRounds().add(new RecruitmentRound());
            assertThat(DrivePhases.phaseOf(open, now)).isEqualTo("ROUNDS");
            assertThat(DrivePhases.stageOf(open, open.getPositions().get(0), now)).isEqualTo("ROUNDS");
            open.getPositions().get(0).setFinalizedAt(now);
            assertThat(DrivePhases.stageOf(open, open.getPositions().get(0), now)).isEqualTo("FINALIZED");
        }
    }

    @Nested
    class InterviewSlots {

        RecruitmentApplication candidate(String id, String applicant) {
            RecruitmentApplication application = new RecruitmentApplication();
            application.setId(id);
            application.setApplicant(applicant);
            return application;
        }

        @Test
        void slotsGoToTheFirstCandidateWhoIsFreeAndRemainingClashesAreReported() {
            Instant start = Instant.parse("2026-10-17T09:30:00Z");
            Map<String, List<SlotPlanner.Busy>> busy = Map.of("asha", List.of(new SlotPlanner.Busy(start, start.plus(20, ChronoUnit.MINUTES), "Technical coordinator")));
            SlotPlanner.Plan plan = SlotPlanner.plan(List.of(candidate("a", "asha"), candidate("b", "bina")), RoundTiming.SLOTS, start, null, 20, busy);
            assertThat(plan.assignments()).extracting(assignment -> assignment.candidate().getApplicant()).containsExactly("bina", "asha");
            assertThat(plan.assignments().get(1).slot().startAt()).isEqualTo(start.plus(20, ChronoUnit.MINUTES));
            assertThat(plan.warnings()).isEmpty();

            SlotPlanner.Plan common = SlotPlanner.plan(List.of(candidate("a", "asha")), RoundTiming.COMMON, start, start.plus(1, ChronoUnit.HOURS), null, busy);
            assertThat(common.warnings()).singleElement().satisfies(warning -> {
                assertThat(warning.applicationId()).isEqualTo("a");
                assertThat(warning.clashWith()).isEqualTo("Technical coordinator");
                assertThat(warning.at()).isEqualTo("Sat, 17 Oct 2026 · 15:00–15:20");
            });
        }

        @Test
        void remindersAreDueAnHourAndTenMinutesBefore() {
            Instant start = Instant.parse("2026-10-17T09:30:00Z");
            assertThat(RecruitmentReminders.reminderFor(start, start.minus(50, ChronoUnit.MINUTES))).isEqualTo(new RecruitmentReminders.Reminder("60m", 60));
            assertThat(RecruitmentReminders.reminderFor(start, start.minus(12, ChronoUnit.MINUTES))).isNull();
            assertThat(RecruitmentReminders.reminderFor(start, start.minus(8, ChronoUnit.MINUTES))).isEqualTo(new RecruitmentReminders.Reminder("10m", 10));
            assertThat(RecruitmentReminders.reminderFor(start, start.plusSeconds(1))).isNull();
        }
    }

    @Nested
    class DriveForm {

        final Instant now = Instant.parse("2026-10-14T10:00:00Z");

        Map<String, Object> payload(Object positions) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("title", "Core team");
            body.put("description", "Builders wanted");
            body.put("positions", positions);
            body.put("applicationEnd", "2026-10-20T10:00:00Z");
            return body;
        }

        /** A Treasurer role whose form has one page, "About", with this question. */
        Map<String, Object> withQuestion(Map<String, Object> question) {
            Map<String, Object> page = Map.of("title", "About", "questions", List.of(question));
            return Map.of("role", "TREASURER", "form", Map.of("pages", List.of(page)));
        }

        String problem(Map<String, Object> body) {
            try {
                DriveForms.normalize(new Club(), body, () -> false, now);
                return null;
            } catch (AppException e) {
                return e.getMessage();
            }
        }

        @Test
        void checksRolesFormsQuestionsAndTheWindowWithTheOriginalMessages() {
            assertThat(problem(payload(List.of()))).isEqualTo("Add at least one role you're recruiting for");
            assertThat(problem(payload(List.of(Map.of("role", "TREASURER", "openings", "two"))))).isEqualTo("Openings for \"Treasurer\" must be a whole number from 1");
            Map<String, Object> sameOptions = Map.of("type", "SINGLE_CHOICE", "label", "Team", "options", List.of("Web", "Web"));
            assertThat(problem(payload(List.of(withQuestion(sameOptions))))).isEqualTo("\"Team\" needs between 2 and 12 different options");
            Map<String, Object> video = Map.of("type", "VIDEO", "label", "Show us");
            assertThat(problem(payload(List.of(withQuestion(video))))).isEqualTo("Question 1 on \"About\" (Treasurer) has an unknown question type");
            Map<String, Object> backwards = payload(List.of(Map.of("role", "TREASURER")));
            backwards.put("applicationEnd", "2026-10-13T10:00:00Z");
            assertThat(problem(backwards)).isEqualTo("Applications must close after they open");
            Map<String, Object> late = payload(List.of(Map.of("role", "TREASURER")));
            late.put("applicationStart", "2026-10-01T10:00:00Z");
            late.put("applicationEnd", "2026-10-13T10:00:00Z");
            assertThat(problem(late)).isEqualTo("The application deadline must be in the future");
            Map<String, Object> long_ = payload(List.of(Map.of("role", "TREASURER")));
            long_.put("applicationEnd", "2027-01-20T10:00:00Z");
            assertThat(problem(long_)).isEqualTo("Applications can stay open for up to 60 days");
            Map<String, Object> batches = payload(List.of(Map.of("role", "TREASURER")));
            batches.put("eligibility", Map.of("batches", List.of("2024")));
            assertThat(problem(batches)).isEqualTo("Batches are two-digit years, e.g. 24");
        }

        @Test
        void theVicePresidencyIsRecruitedForOneSeatAndKeptIdsSurvive() {
            String id = "64b000000000000000000001";
            DriveForms.NormalizedDrive drive = DriveForms.normalize(new Club(), payload(List.of(Map.of("_id", id, "role", "VICE_PRESIDENT", "openings", 3))), () -> false, now);
            assertThat(drive.positions().get(0).getOpenings()).isEqualTo(1);
            assertThat(drive.positions().get(0).getId()).isEqualTo(id);
            assertThat(drive.positions().get(0).getTitle()).isEqualTo("Vice-president");
            assertThat(drive.window().applicationStart()).isEqualTo(now);
            assertThatThrownBy(() -> DriveForms.normalize(new Club(), payload(List.of(Map.of("role", "VICE_PRESIDENT"))), () -> true, now))
                    .hasMessage("Your club already has a vice-president");
        }
    }
}
