package com.campusconnect.feature.recruitment.selection;

import com.campusconnect.core.util.CampusTime;
import com.campusconnect.domain.recruitment.RecruitmentApplication;
import com.campusconnect.domain.recruitment.RoundTiming;
import com.campusconnect.feature.recruitment.dto.SelectionViews.Warning;
import com.campusconnect.feature.recruitment.mail.RecruitmentMailer.Slot;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Who gets which interview time (RecruitmentRoundService.js scheduleRound). COMMON: one time for everyone. SLOTS:
 * back-to-back slots, each going to the first waiting candidate who is free then — candidates also interviewing for
 * another role of the drive are placed around those interviews; anyone who still clashes is reported.
 */
public final class SlotPlanner {

    private SlotPlanner() {
    }

    /** An interview the candidate already has for another role. */
    public record Busy(Instant startAt, Instant endAt, String role) {
    }

    public record Assignment(RecruitmentApplication candidate, Slot slot) {
    }

    public record Plan(List<Assignment> assignments, List<Warning> warnings) {
    }

    static boolean overlaps(Instant startA, Instant endA, Instant startB, Instant endB) {
        return startA.isBefore(endB) && startB.isBefore(endA);
    }

    static List<Busy> clashes(Map<String, List<Busy>> busy, RecruitmentApplication candidate, Slot slot) {
        return busy.getOrDefault(candidate.getApplicant(), List.of()).stream().filter(other -> overlaps(slot.startAt(), slot.endAt(), other.startAt(), other.endAt())).toList();
    }

    static Warning warning(RecruitmentApplication candidate, Busy clash) {
        return new Warning(candidate.getId(), clash.role(), CampusTime.formatSchedule(clash.startAt(), clash.endAt()));
    }

    /**
     * @param candidates the round's candidates in application order
     * @param busy       applicant id to their interviews for other roles
     */
    public static Plan plan(List<RecruitmentApplication> candidates, RoundTiming timing, Instant startAt, Instant endAt, Integer slotMinutes, Map<String, List<Busy>> busy) {
        List<Assignment> assignments = new ArrayList<>();
        List<Warning> warnings = new ArrayList<>();
        if (timing == RoundTiming.COMMON) {
            Slot slot = new Slot(startAt, endAt);
            for (RecruitmentApplication candidate : candidates) {
                assignments.add(new Assignment(candidate, slot));
                List<Busy> clash = clashes(busy, candidate, slot);
                if (!clash.isEmpty()) {
                    warnings.add(warning(candidate, clash.get(0)));
                }
            }
            return new Plan(assignments, warnings);
        }
        Duration length = Duration.ofMinutes(slotMinutes);
        List<RecruitmentApplication> waiting = new ArrayList<>(candidates);
        for (int index = 0; index < candidates.size(); index++) {
            Slot slot = new Slot(startAt.plus(length.multipliedBy(index)), startAt.plus(length.multipliedBy(index + 1L)));
            int pick = -1;
            for (int i = 0; i < waiting.size(); i++) {
                if (clashes(busy, waiting.get(i), slot).isEmpty()) {
                    pick = i;
                    break;
                }
            }
            if (pick == -1) {
                pick = 0;
                warnings.add(warning(waiting.get(0), clashes(busy, waiting.get(0), slot).get(0)));
            }
            assignments.add(new Assignment(waiting.get(pick), slot));
            waiting.remove(pick);
        }
        return new Plan(assignments, warnings);
    }
}
