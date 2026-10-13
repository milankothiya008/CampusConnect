package com.campusconnect.feature.recruitment.dto;

import com.campusconnect.core.web.validation.TrimmedSize;
import com.campusconnect.feature.recruitment.support.IsoTimes;
import com.campusconnect.feature.recruitment.support.Js;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Request bodies of /api/recruitment (the express-validator rules of routes/RecruitmentRoutes.js, same messages).
 * Drive bodies stay raw JSON maps: their roles, pages and questions are normalised by the service exactly as before.
 * Numbers the original rules checked with {@code isInt} on any JSON type ({@code slotMinutes}, {@code offerDays}) are
 * kept as raw values and checked by the controller.
 */
public final class RecruitmentRequests {

    static final String ISO8601_OR_EMPTY = "^$|" + IsoTimes.ISO8601;

    private RecruitmentRequests() {
    }

    /**
     * The rules of PUT /api/recruitment/:id (driveRules), checked on the raw body because they test JSON types:
     * one message per failed rule, in rule order.
     */
    public static List<String> driveProblems(Map<String, Object> body) {
        List<String> problems = new ArrayList<>();
        if (body == null) {
            return problems;
        }
        text(body, "title", 120, problems);
        text(body, "description", 4000, problems);
        array(body, "positions", 10, "Up to 10 positions", problems);
        array(body, "questions", 20, "Up to 20 questions", problems);
        Object start = body.get("applicationStart");
        if (Js.truthy(start) && !IsoTimes.isIso8601(Js.validatorString(start))) {
            problems.add("Invalid opening time");
        }
        if (body.containsKey("applicationEnd") && !IsoTimes.isIso8601(Js.validatorString(body.get("applicationEnd")))) {
            problems.add("Invalid deadline");
        }
        return problems;
    }

    /** {@code optional().isString().isLength({ max })}. */
    private static void text(Map<String, Object> body, String field, int max, List<String> problems) {
        if (!body.containsKey(field)) {
            return;
        }
        Object value = body.get(field);
        if (!(value instanceof String)) {
            problems.add("Invalid value");
        }
        if (Js.validatorString(value).length() > max) {
            problems.add("Invalid value");
        }
    }

    /** {@code optional().isArray({ max }).withMessage(message)}. */
    private static void array(Map<String, Object> body, String field, int max, String message, List<String> problems) {
        if (body.containsKey(field) && !(body.get(field) instanceof List<?> list && list.size() <= max)) {
            problems.add(message);
        }
    }

    /** {@code optional({ values: "falsy" }).isInt({ min: 5, max: 240 })} of a schedule's slot length. */
    public static boolean slotMinutesValid(Object value) {
        return !Js.truthy(value) || Js.isInt(value, 5, 240);
    }

    /** {@code optional().isInt({ min: 1, max: 14 })} of the days to answer an offer. */
    public static boolean offerDaysValid(Object value) {
        return value == null || Js.isInt(value, 1, 14);
    }

    /** approve: optional note. */
    public record CommentBody(@Size(max = 2000, message = "Invalid value") String comment) {
    }

    /** request-changes and reject: a reason of at least 5 characters. */
    public record RequiredCommentBody(
            @NotNull(message = "Explain in at least 5 characters")
            @TrimmedSize(min = 5, max = 2000, message = "Explain in at least 5 characters") String comment) {
    }

    public record DeadlineBody(
            @NotNull(message = "Choose a new deadline") @Pattern(regexp = IsoTimes.ISO8601, message = "Choose a new deadline") String applicationEnd) {
    }

    public record CancelBody(@Size(max = 500, message = "Invalid value") String reason) {
    }

    /** One answer per question: { question, text?, choices?, media?, keepFile? }. */
    public record AnswersBody(@Size(max = 200, message = "Invalid value") List<Object> answers) {
    }

    public record UploadsBody(
            @NotNull(message = "Invalid value") @Size(min = 1, max = 10, message = "Invalid value")
            List<@NotNull(message = "Attach PDFs or images") @Pattern(regexp = "DOCUMENT|IMAGE", message = "Attach PDFs or images") String> kinds) {
    }

    /** The mobile number may come with the acceptance (club members must have one). */
    public record AcceptBody(Object phone) {
    }

    public record RoundBody(
            @NotNull(message = "Name the round") @TrimmedSize(min = 1, max = 80, message = "Name the round") String name,
            @NotNull(message = "Choose the round type") @Pattern(regexp = "SCREENING|ONLINE|OFFLINE", message = "Choose the round type") String mode) {
    }

    public record ScheduleBody(
            @NotNull(message = "Choose one common time or individual slots")
            @Pattern(regexp = "COMMON|SLOTS", message = "Choose one common time or individual slots") String timing,
            @NotNull(message = "Set the start time") @Pattern(regexp = IsoTimes.ISO8601, message = "Set the start time") String startAt,
            @Pattern(regexp = ISO8601_OR_EMPTY, message = "Invalid value") String endAt,
            Object slotMinutes,
            @Pattern(regexp = "^$|^[0-9a-fA-F]{24}$", message = "Invalid value") String venue,
            @Size(max = 500, message = "Invalid value") String meetingLink,
            @Size(max = 1000, message = "Invalid value") String instructions) {
    }

    public record SlotBody(@NotNull(message = "Choose the new time") @Pattern(regexp = IsoTimes.ISO8601, message = "Choose the new time") String startAt) {
    }

    /** Draft results of the current round: [{ applicationId, outcome: QUALIFIED | ELIMINATED | null, note? }]. */
    public record OutcomesBody(@NotNull(message = "Invalid value") @Size(max = 1000, message = "Invalid value") List<Map<String, Object>> decisions) {
    }

    /** Final selection: [{ applicationId, decision: OFFER | RESERVE | NOT_SELECTED }] and the days to answer an offer. */
    public record FinalizeBody(
            @NotNull(message = "Invalid value") @Size(max = 1000, message = "Invalid value") List<Map<String, Object>> decisions,
            Object offerDays) {
    }
}
