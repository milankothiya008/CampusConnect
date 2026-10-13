package com.campusconnect.feature.recruitment.drive;

import static com.campusconnect.feature.recruitment.drive.Drives.invalid;

import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubRole;
import com.campusconnect.domain.common.SubDocument;
import com.campusconnect.domain.recruitment.PositionForm;
import com.campusconnect.domain.recruitment.QuestionType;
import com.campusconnect.domain.recruitment.RecruitmentPage;
import com.campusconnect.domain.recruitment.RecruitmentPosition;
import com.campusconnect.domain.recruitment.RecruitmentQuestion;
import com.campusconnect.feature.recruitment.support.IsoTimes;
import com.campusconnect.feature.recruitment.support.Js;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.bson.types.ObjectId;

/**
 * Validation of a drive the president builds (RecruitmentService.js normalizeDrive): roles from the club (never the
 * presidency, the vice-presidency only into a free seat), a titled page-wise form per role, the batches and the
 * application window. The body is free-form JSON, read with JavaScript's coercions so messages stay the same.
 * Ids of roles, pages and questions that are sent back are kept, so answers keep pointing at their questions.
 */
public final class DriveForms {

    static final int MAX_POSITIONS = 10;
    static final int MAX_PAGES = 8;
    static final int MAX_QUESTIONS_PER_PAGE = 20;
    static final int MAX_OPTIONS = 12;
    public static final int MAX_WINDOW_DAYS = 60;
    static final long MAX_WINDOW_MILLIS = MAX_WINDOW_DAYS * 86_400_000L;
    private static final List<QuestionType> CHOICE_TYPES = List.of(QuestionType.SINGLE_CHOICE, QuestionType.MULTI_CHOICE);
    private static final Pattern BATCH = Pattern.compile("^\\d{2}$");

    private DriveForms() {
    }

    public record Window(Instant applicationStart, Instant applicationEnd) {
    }

    public record NormalizedDrive(String title, String description, List<RecruitmentPosition> positions, List<String> batches, Window window) {
    }

    /**
     * @param viceTaken asked once, and only if the vice-presidency is listed: does the club already have a
     *                  vice-president?
     */
    public static NormalizedDrive normalize(Club club, Map<String, Object> payload, BooleanSupplier viceTaken, Instant now) {
        String title = Js.clean(payload.get("title"), 120);
        String description = Js.clean(payload.get("description"), 4000);
        if (title.isEmpty()) {
            throw invalid("Give the drive a title");
        }
        if (description.isEmpty()) {
            throw invalid("Describe what the club is looking for");
        }
        List<RecruitmentPosition> positions = positions(club, payload.get("positions"), viceTaken);
        Object eligibility = payload.get("eligibility");
        List<String> batches = batches(eligibility instanceof Map<?, ?> map ? map.get("batches") : null);
        Window window = window(payload.get("applicationStart"), payload.containsKey("applicationEnd"), payload.get("applicationEnd"), now);
        return new NormalizedDrive(title, description, positions, batches, window);
    }

    // ---------------------------------------------------------------- roles

    static List<RecruitmentPosition> positions(Club club, Object raw, BooleanSupplier viceTaken) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            throw invalid("Add at least one role you're recruiting for");
        }
        if (list.size() > MAX_POSITIONS) {
            throw invalid("Up to " + MAX_POSITIONS + " roles per drive");
        }
        Set<String> seen = new HashSet<>();
        Boolean vice = null;
        List<RecruitmentPosition> result = new ArrayList<>();
        for (Object item : list) {
            Map<?, ?> position = item instanceof Map<?, ?> map ? map : Map.of();
            String key = Js.truthy(position.get("role")) ? Js.string(position.get("role")) : "";
            ClubRole role = ClubRoles.findRole(club, key);
            if (role == null || ClubRoles.PRESIDENT.equals(key)) {
                throw invalid("Recruit for one of your club's roles (the presidency is handed over, not recruited)");
            }
            if (!seen.add(key)) {
                throw invalid("\"" + role.getName() + "\" is listed twice");
            }
            boolean isVice = ClubRoles.VICE_PRESIDENT.equals(key);
            if (isVice) {
                if (vice == null) {
                    vice = viceTaken.getAsBoolean();
                }
                if (vice) {
                    throw Drives.invalidState("Your club already has a vice-president");
                }
            }
            Object rawOpenings = position.get("openings");
            Integer openings = null;
            if (rawOpenings != null && !"".equals(rawOpenings)) {
                double number = Js.number(rawOpenings);
                if (!Js.isInteger(number) || number < 1 || number > 500) {
                    throw invalid("Openings for \"" + role.getName() + "\" must be a whole number from 1");
                }
                openings = (int) number;
            }
            if (isVice) {
                openings = 1;
            }
            RecruitmentPosition out = new RecruitmentPosition();
            keepId(out, position.get("_id"));
            out.setRole(key);
            out.setTitle(role.getName());
            out.setOpenings(openings);
            out.setDescription(Js.clean(position.get("description"), 400));
            out.setForm(form(position.get("form"), role.getName()));
            result.add(out);
        }
        return result;
    }

    static PositionForm form(Object raw, String roleTitle) {
        Object rawPages = raw instanceof Map<?, ?> map ? map.get("pages") : null;
        List<?> pages = rawPages instanceof List<?> list ? list : List.of();
        if (pages.size() > MAX_PAGES) {
            throw invalid("The " + roleTitle + " form can have up to " + MAX_PAGES + " pages");
        }
        List<RecruitmentPage> result = new ArrayList<>();
        for (int index = 0; index < pages.size(); index++) {
            Map<?, ?> page = pages.get(index) instanceof Map<?, ?> map ? map : Map.of();
            String title = Js.clean(page.get("title"), 80);
            if (title.isEmpty()) {
                throw invalid("Page " + (index + 1) + " of the " + roleTitle + " form needs a title");
            }
            List<?> questions = page.get("questions") instanceof List<?> list ? list : List.of();
            if (questions.size() > MAX_QUESTIONS_PER_PAGE) {
                throw invalid("A page can have up to " + MAX_QUESTIONS_PER_PAGE + " questions");
            }
            RecruitmentPage out = new RecruitmentPage();
            keepId(out, page.get("_id"));
            out.setTitle(title);
            out.setDescription(Js.clean(page.get("description"), 300));
            List<RecruitmentQuestion> normalized = new ArrayList<>();
            for (int number = 0; number < questions.size(); number++) {
                normalized.add(question(questions.get(number), "Question " + (number + 1) + " on \"" + title + "\" (" + roleTitle + ")"));
            }
            out.setQuestions(normalized);
            result.add(out);
        }
        PositionForm form = new PositionForm();
        form.setPages(result);
        return form;
    }

    static RecruitmentQuestion question(Object raw, String where) {
        Map<?, ?> question = raw instanceof Map<?, ?> map ? map : Map.of();
        String typeName = Js.truthy(question.get("type")) ? Js.string(question.get("type")) : "";
        QuestionType type = Arrays.stream(QuestionType.values()).filter(value -> value.name().equals(typeName)).findFirst().orElse(null);
        if (type == null) {
            throw invalid(where + " has an unknown question type");
        }
        String label = Js.clean(question.get("label"), 200);
        if (label.isEmpty()) {
            throw invalid(where + " needs a question text");
        }
        List<String> options = new ArrayList<>();
        if (CHOICE_TYPES.contains(type)) {
            List<?> given = question.get("options") instanceof List<?> list ? list : List.of();
            Set<String> unique = new LinkedHashSet<>();
            given.stream().map(option -> Js.clean(option, 100)).filter(option -> !option.isEmpty()).forEach(unique::add);
            if (unique.size() < 2 || unique.size() > MAX_OPTIONS) {
                throw invalid("\"" + label + "\" needs between 2 and " + MAX_OPTIONS + " different options");
            }
            options.addAll(unique);
        }
        RecruitmentQuestion out = new RecruitmentQuestion();
        keepId(out, question.get("_id"));
        out.setType(type);
        out.setLabel(label);
        out.setHelp(Js.clean(question.get("help"), 300));
        out.setRequired(Js.truthy(question.get("required")));
        out.setOptions(options);
        return out;
    }

    private static void keepId(SubDocument target, Object id) {
        String value = Js.truthy(id) ? Js.string(id) : null;
        if (value != null && ObjectId.isValid(value)) {
            target.setId(new ObjectId(value).toHexString());
        }
    }

    // ---------------------------------------------------------------- eligibility and dates

    static List<String> batches(Object raw) {
        List<?> list = raw instanceof List<?> values ? values : List.of();
        Set<String> unique = new LinkedHashSet<>();
        list.forEach(batch -> unique.add(Js.trim(batch == null ? "null" : Js.string(batch))));
        if (unique.stream().anyMatch(batch -> !BATCH.matcher(batch).matches())) {
            throw invalid("Batches are two-digit years, e.g. 24");
        }
        return new ArrayList<>(unique);
    }

    /**
     * The application window: opens now unless a start is given, closes after it opens and in the future, and stays
     * open for at most {@value #MAX_WINDOW_DAYS} days.
     */
    static Window window(Object rawStart, boolean endGiven, Object rawEnd, Instant now) {
        Instant start = Js.truthy(rawStart) ? IsoTimes.toInstant(rawStart) : now;
        Instant end = endGiven ? IsoTimes.toInstant(rawEnd) : null;
        return window(start, end, now);
    }

    public static Window window(Instant start, Instant end, Instant now) {
        if (start == null || end == null) {
            throw invalid("Set when applications open and close");
        }
        if (!end.isAfter(start)) {
            throw invalid("Applications must close after they open");
        }
        if (!end.isAfter(now)) {
            throw invalid("The application deadline must be in the future");
        }
        if (end.toEpochMilli() - start.toEpochMilli() > MAX_WINDOW_MILLIS) {
            throw invalid("Applications can stay open for up to " + MAX_WINDOW_DAYS + " days");
        }
        return new Window(start, end);
    }
}
