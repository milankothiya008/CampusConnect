package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.error.AppException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/** express-validator semantics of {@link RequestChecks}: one message per failing validator, optional modes, sanitizers. */
class RequestChecksTest {

    static Map<String, Object> body(Object... pairs) {
        Map<String, Object> body = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            body.put((String) pairs[i], pairs[i + 1]);
        }
        return body;
    }

    static String message(RequestChecks checks) {
        try {
            checks.validate();
            return null;
        } catch (AppException e) {
            assertThat(e.getStatus()).isEqualTo(400);
            return e.getMessage();
        }
    }

    @Test
    void aMissingRequiredTextFailsItsTypeCheckAndItsLengthCheck() {
        RequestChecks checks = RequestChecks.of(body());
        checks.field("name").isString().trim().isLength(3, 120).withMessage("Club name must be 3-120 characters");
        assertThat(message(checks)).isEqualTo("Invalid value. Club name must be 3-120 characters");
    }

    @Test
    void optionalSkipsOnlyMissingFieldsOptionalNullAlsoSkipsNullOptionalFalsySkipsEmptyValues() {
        RequestChecks missing = RequestChecks.of(body());
        missing.field("name").optional().isString().isLength(2, 40).withMessage("Role names are 2 to 40 characters");
        assertThat(message(missing)).isNull();

        RequestChecks explicitNull = RequestChecks.of(body("name", null));
        explicitNull.field("name").optional().isString().isLength(2, 40).withMessage("Role names are 2 to 40 characters");
        assertThat(message(explicitNull)).isEqualTo("Invalid value. Role names are 2 to 40 characters");

        RequestChecks nullAllowed = RequestChecks.of(body("tagline", null));
        nullAllowed.field("tagline").optionalNull().isString().isLength(0, 140);
        assertThat(message(nullAllowed)).isNull();

        for (Object falsy : Arrays.asList("", 0, false, null)) {
            RequestChecks checks = RequestChecks.of(body("contactEmail", falsy));
            checks.field("contactEmail").optionalFalsy().isEmail().withMessage("Invalid contact email");
            assertThat(message(checks)).as(String.valueOf(falsy)).isNull();
        }
    }

    @Test
    void sanitizersWriteTheCleanedValueBack() {
        Map<String, Object> body = body("name", "  Robotics  ", "allDepartments", "true");
        RequestChecks checks = RequestChecks.of(body);
        checks.field("name").isString().trim().isLength(3, 120);
        checks.field("allDepartments").optional().isBoolean().toBoolean();
        assertThat(message(checks)).isNull();
        assertThat(body).containsEntry("name", "Robotics").containsEntry("allDepartments", true);
    }

    @Test
    void wildcardsCheckEveryElementAndPathParametersComeFirst() {
        Map<String, Object> body = body("departmentCodes", new ArrayList<>(Arrays.asList(" ce ", 5)));
        RequestChecks checks = RequestChecks.of(body).param("id", "not-an-id");
        checks.field("departmentCodes").optional().isArray(30).withMessage("departmentCodes must be a list");
        checks.field("departmentCodes.*").isString().trim().isLength(2, 4).withMessage("Invalid department code");
        assertThat(message(checks)).isEqualTo("Invalid id. Invalid value. Invalid department code");
        assertThat((List<Object>) body.get("departmentCodes")).containsExactly("ce", "5");
    }

    @Test
    void standardValidatorsCheckEachElementOfAnArray() {
        RequestChecks checks = RequestChecks.of(body("category", List.of("TECHNOLOGY", "NOPE", "ALSO")));
        checks.field("category").isIn(List.of("TECHNOLOGY", "SPORTS")).withMessage("Invalid category");
        assertThat(message(checks)).isEqualTo("Invalid category. Invalid category");
    }

    @Test
    void mongoIdsBooleansIntegersAndPatterns() {
        RequestChecks ids = RequestChecks.of(body("userId", "0123456789abcdef01234567")).mongoId("userId");
        assertThatCode(ids::validate).doesNotThrowAnyException();
        assertThat(message(RequestChecks.of(body()).mongoId("userId"))).isEqualTo("A valid userId is required");

        for (Object accepted : List.of(true, false, "true", "false", "1", "0", 1, 0)) {
            RequestChecks checks = RequestChecks.of(body("enabled", accepted));
            checks.field("enabled").isBoolean().withMessage("enabled must be true or false");
            assertThat(message(checks)).as(String.valueOf(accepted)).isNull();
        }
        RequestChecks yes = RequestChecks.of(body("enabled", "yes"));
        yes.field("enabled").isBoolean().withMessage("enabled must be true or false");
        assertThat(message(yes)).isEqualTo("enabled must be true or false");

        RequestChecks capacity = RequestChecks.of(body("capacity", "0"));
        capacity.field("capacity").isInt(1, 100_000).withMessage("Capacity must be a positive number");
        assertThat(message(capacity)).isEqualTo("Capacity must be a positive number");
        RequestChecks fine = RequestChecks.of(body("capacity", 250));
        fine.field("capacity").isInt(1, 100_000);
        assertThat(message(fine)).isNull();

        RequestChecks batch = RequestChecks.of(body("code", 24));
        batch.field("code").matches(Pattern.compile("^\\d{2}$")).withMessage("Batch code must be 2 digits");
        assertThat(message(batch)).isNull();
    }

    @Test
    void emailsFollowValidatorJs() {
        assertThat(RequestChecks.isEmail("24ceuog001@ddu.ac.in")).isTrue();
        assertThat(RequestChecks.isEmail("first.last+tag@example.co")).isTrue();
        assertThat(RequestChecks.isEmail("someone@localhost")).isFalse();
        assertThat(RequestChecks.isEmail("two words@ddu.ac.in")).isFalse();
        assertThat(RequestChecks.isEmail("dots..twice@ddu.ac.in")).isFalse();
        assertThat(RequestChecks.isEmail("@ddu.ac.in")).isFalse();
        assertThat(RequestChecks.isEmail("someone@ddu.ac.123")).isFalse();
    }

    @Test
    void lengthCountsCharactersNotUtf16Units() {
        RequestChecks checks = RequestChecks.of(body("name", "🙂🙂"));
        checks.field("name").isLength(2, 2);
        assertThat(message(checks)).isNull();
    }

    @Test
    void validateThrowsAValidationError() {
        RequestChecks checks = RequestChecks.of(body());
        checks.field("role").isString().trim().isLength(1, 60).withMessage("Choose a role");
        assertThatThrownBy(checks::validate).isInstanceOf(AppException.class).hasFieldOrPropertyWithValue("status", 400);
    }
}
