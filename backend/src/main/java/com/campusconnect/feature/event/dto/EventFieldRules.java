package com.campusconnect.feature.event.dto;

import com.campusconnect.core.util.Categories;
import com.campusconnect.feature.event.dto.BodyChecks.Chain;
import com.campusconnect.feature.event.dto.BodyChecks.Skip;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * eventDraftRules / eventUpdateRules (validators/RequestValidators.js eventFields): the create body needs every
 * required field, the edit body checks only the fields it sends. Trimming sanitisers update the body, as
 * express-validator did.
 */
public final class EventFieldRules {

    private static final Pattern TIME = Pattern.compile("^([01]\\d|2[0-3]):([0-5]\\d)$");
    private static final Pattern DATE = Pattern.compile("^\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern URL_OR_UPLOAD = Pattern.compile("^(https?://|/uploads/)", Pattern.CASE_INSENSITIVE);

    private EventFieldRules() {
    }

    /** Validates (and trims) a create body; throws 400 with the joined messages. */
    public static void checkDraft(Map<String, Object> body) {
        BodyChecks checks = new BodyChecks(body);
        checks.field("club").isMongoId().withMessage("Club is required");
        eventFields(checks, false);
        checks.throwIfInvalid();
    }

    /** Validates (and trims) an edit body; throws 400 with the joined messages. */
    public static void checkUpdate(Map<String, Object> body) {
        BodyChecks checks = new BodyChecks(body);
        eventFields(checks, true);
        checks.throwIfInvalid();
    }

    private static void eventFields(BodyChecks checks, boolean optional) {
        java.util.function.Function<String, Chain> f = field -> optional ? checks.field(field).optional() : checks.field(field);
        f.apply("title").isString().trim().isLength(3, 160).withMessage("Title must be 3-160 characters");
        f.apply("shortDescription").isString().trim().isLength(10, 280).withMessage("Short description must be 10-280 characters");
        f.apply("description").isString().trim().isLength(10, 8000).withMessage("Description must be 10-8000 characters");
        f.apply("category").isIn(Categories.EVENT_CATEGORIES).withMessage("Invalid category");
        f.apply("venue").isMongoId().withMessage("Venue is required");
        f.apply("eventDate").matches(DATE).withMessage("Event date must be YYYY-MM-DD");
        checks.field("endDate").optional(Skip.NULL).matches(DATE).withMessage("End date must be YYYY-MM-DD");
        checks.field("certificatesEnabled").optional().isBoolean();
        checks.field("registrationForm.enabled").optional().isBoolean();
        checks.field("registrationForm.questions").optional().isArray(null, 25);
        checks.field("budgetItems").optional().isArray(null, 40);
        checks.field("equipment").optional().isArray(null, 40);
        checks.field("budgetNote").optional().isString().isLength(0, 1000);
        f.apply("startTime").matches(TIME).withMessage("Start time must be HH:mm");
        f.apply("endTime").matches(TIME).withMessage("End time must be HH:mm");
        f.apply("registrationEnd").isISO8601().withMessage("Registration deadline is required");
        checks.field("registrationStart").optional(Skip.FALSY).isISO8601().withMessage("Invalid registration start");
        checks.field("maxParticipants").optional(Skip.NULL).isInt(1, 100_000).withMessage("Participant limit must be a positive number");
        checks.field("eligibility.departments").optional().isArray(null, null);
        checks.field("eligibility.batches").optional().isArray(null, null);
        checks.field("eligibility.notes").optional().isString().isLength(0, 1000);
        checks.field("rules").optional().isString().isLength(0, 8000);
        checks.field("contact.name").optional().isString().isLength(0, 120);
        checks.field("contact.email").optional(Skip.FALSY).isEmail().withMessage("Invalid contact email");
        checks.field("contact.phone").optional().isString().isLength(0, 30);
        checks.field("organizer").optional(Skip.FALSY).isMongoId();
        checks.field("registrationClosed").optional().isBoolean();
        checks.field("updateNote").optional().isString().isLength(0, 2000);
        checks.field("participationMode").optional().isIn(List.of("INDIVIDUAL", "TEAM")).withMessage("Choose individual or team entry");
        checks.field("minTeamSize").optional(Skip.NULL).isInt(1, 20).withMessage("Minimum team size must be 1-20");
        checks.field("maxTeamSize").optional(Skip.NULL).isInt(1, 20).withMessage("Maximum team size must be 1-20");
        checks.field("poster").optional(Skip.NULL).isString()
                .custom(value -> "".equals(value) || (value instanceof String text && URL_OR_UPLOAD.matcher(text).find()))
                .withMessage("poster must be an http(s) URL or an uploaded file");
    }
}
