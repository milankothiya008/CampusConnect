package com.campusconnect.feature.auth;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.domain.user.AccountType;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * University e-mail formats (utils/UniversityRules.js). The account type, department and batch are derived
 * from the verified address, never from client input.
 */
@Component
public class UniversityRules {

    /** Student: batch (2 digits), department (2-4 letters), identity (3 letters), number (3 digits), e.g. 24ceuog001. */
    private static final Pattern STUDENT = Pattern.compile("^(\\d{2})([a-z]{2,4})([a-z]{3})(\\d{3})$", Pattern.CASE_INSENSITIVE);
    /** Faculty: first name . department, e.g. mrudang.ce. */
    private static final Pattern FACULTY = Pattern.compile("^([a-z]+)\\.([a-z]{2,4})$", Pattern.CASE_INSENSITIVE);

    public record StudentParts(String batchCode, String departmentCode, String identifier) {
    }

    public record FacultyParts(String departmentCode, String namePart) {
    }

    public record Classified(AccountType accountType, String batchCode, String departmentCode) {
    }

    private final String domain;
    private final MongoOperations mongo;

    public UniversityRules(AppProperties properties, MongoOperations mongo) {
        this.domain = properties.getUniversity().getDomain().toLowerCase(Locale.ROOT);
        this.mongo = mongo;
    }

    public String domain() {
        return domain;
    }

    public String studentFormatHint() {
        return "Student emails must be <batch><department><identity><number>@" + domain + ", e.g. 24ceuog001@" + domain + " (2-digit batch, 2-4 letter department, 3 letters, 3 digits)";
    }

    public String facultyFormatHint() {
        return "Faculty emails must be <first name>.<department>@" + domain + ", e.g. mrudang.ce@" + domain;
    }

    public String emailFormatHint() {
        return "student (e.g. 24ceuog001@" + domain + ") or faculty (e.g. mrudang.ce@" + domain + ")";
    }

    public boolean isUniversityEmail(String email) {
        return String.valueOf(email).toLowerCase(Locale.ROOT).endsWith("@" + domain);
    }

    private static String localPart(String email) {
        return String.valueOf(email).trim().split("@", -1)[0];
    }

    public StudentParts parseStudentEmail(String email) {
        Matcher match = STUDENT.matcher(localPart(email));
        if (!match.matches()) {
            return null;
        }
        return new StudentParts(match.group(1), match.group(2).toUpperCase(Locale.ROOT), (match.group(3) + match.group(4)).toUpperCase(Locale.ROOT));
    }

    public FacultyParts parseFacultyEmail(String email) {
        Matcher match = FACULTY.matcher(localPart(email));
        if (!match.matches()) {
            return null;
        }
        return new FacultyParts(match.group(2).toUpperCase(Locale.ROOT), match.group(1).toLowerCase(Locale.ROOT));
    }

    /** The university admin signs in as admin@domain, which follows neither format (sign-in only). */
    public String adminEmail() {
        return "admin@" + domain;
    }

    public boolean isAdminEmail(String email) {
        return String.valueOf(email).trim().toLowerCase(Locale.ROOT).equals(adminEmail());
    }

    /** Format-only check (no database lookups), used at sign-in. */
    public boolean isValidUniversityEmailFormat(String email) {
        return isUniversityEmail(email) && (parseStudentEmail(email) != null || parseFacultyEmail(email) != null || isAdminEmail(email));
    }

    private boolean activeCode(String collection, String code) {
        return mongo.exists(Query.query(Criteria.where("code").is(code).and("isActive").is(true)), collection);
    }

    public Classified classify(String email) {
        String normalized = String.valueOf(email).trim().toLowerCase(Locale.ROOT);
        if (!isUniversityEmail(normalized)) {
            throw AppException.badRequest("Only @" + domain + " email addresses are allowed", ErrorCode.INVALID_EMAIL);
        }
        StudentParts student = parseStudentEmail(normalized);
        if (student != null) {
            if (!activeCode("academicbatches", student.batchCode())) {
                throw AppException.badRequest("Batch '" + student.batchCode() + "' is not an allowed academic batch", ErrorCode.INVALID_EMAIL);
            }
            if (!activeCode("departments", student.departmentCode())) {
                throw AppException.badRequest("Department '" + student.departmentCode() + "' is not recognized", ErrorCode.INVALID_EMAIL);
            }
            return new Classified(AccountType.STUDENT, student.batchCode(), student.departmentCode());
        }
        FacultyParts faculty = parseFacultyEmail(normalized);
        if (faculty != null) {
            if (!activeCode("departments", faculty.departmentCode())) {
                throw AppException.badRequest("Department '" + faculty.departmentCode() + "' is not recognized", ErrorCode.INVALID_EMAIL);
            }
            return new Classified(AccountType.FACULTY, null, faculty.departmentCode());
        }
        throw AppException.badRequest("Email must follow the " + emailFormatHint() + " format", ErrorCode.INVALID_EMAIL);
    }
}
