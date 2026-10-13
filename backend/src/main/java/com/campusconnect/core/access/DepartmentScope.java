package com.campusconnect.core.access;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * A club (and the request that creates it) is open to all departments or limited to a list (utils/DepartmentScope.js).
 * The scope decides which faculty may mentor it and which students may belong to it.
 */
@Component
public class DepartmentScope {

    /** allDepartments, or the department codes. */
    public record Scope(boolean allDepartments, List<String> departmentCodes) {
    }

    /** Someone checked against a scope. */
    public record Person(String name, String departmentCode) {
        public static Person of(AuthUser user) {
            return new Person(user.name(), user.departmentCode());
        }
    }

    private final MongoOperations mongo;

    public DepartmentScope(MongoOperations mongo) {
        this.mongo = mongo;
    }

    public static String describe(Scope scope) {
        return scope.allDepartments() ? "all departments" : String.join(", ", scope.departmentCodes());
    }

    public static boolean belongs(String departmentCode, Scope scope) {
        return scope.allDepartments() || scope.departmentCodes().contains(String.valueOf(departmentCode == null ? "" : departmentCode).toUpperCase(Locale.ROOT));
    }

    /** Mongo condition matching clubs/requests that include the department. */
    public static Criteria includes(String departmentCode) {
        return new Criteria().orOperator(Criteria.where("allDepartments").is(true), Criteria.where("departmentCodes").is(String.valueOf(departmentCode).toUpperCase(Locale.ROOT)));
    }

    /** Validates a requested scope: all departments, or at least one known, active department. */
    public Scope resolve(Object allDepartments, Collection<?> departmentCodes) {
        if (Boolean.TRUE.equals(allDepartments) || "true".equals(allDepartments)) {
            return new Scope(true, List.of());
        }
        Set<String> codes = new LinkedHashSet<>();
        if (departmentCodes != null) {
            departmentCodes.stream().map(code -> String.valueOf(code).trim().toUpperCase(Locale.ROOT)).filter(code -> !code.isEmpty()).forEach(codes::add);
        }
        if (codes.isEmpty()) {
            throw AppException.badRequest("Choose at least one department, or open the club to all departments", ErrorCode.VALIDATION_ERROR);
        }
        Query query = Query.query(Criteria.where("code").in(codes).and("isActive").is(true));
        query.fields().include("code");
        Set<String> found = mongo.find(query, Document.class, "departments").stream().map(row -> row.getString("code")).collect(Collectors.toSet());
        List<String> unknown = codes.stream().filter(code -> !found.contains(code)).toList();
        if (!unknown.isEmpty()) {
            throw AppException.badRequest("Department not recognized: " + String.join(", ", unknown), ErrorCode.VALIDATION_ERROR);
        }
        return new Scope(false, new ArrayList<>(codes));
    }

    public static void assertMentorMatches(Person faculty, Scope scope) {
        if (!belongs(faculty.departmentCode(), scope)) {
            throw AppException.badRequest(faculty.name() + " is from " + (faculty.departmentCode() != null ? faculty.departmentCode() : "another department")
                    + ". A club for " + describe(scope) + " needs a faculty mentor from "
                    + (scope.departmentCodes().size() > 1 ? "one of those departments" : "that department") + ".", ErrorCode.VALIDATION_ERROR);
        }
    }

    /** Students can only belong to clubs of their own department (or clubs open to all departments). */
    public static void assertStudentsMatch(List<Person> students, Scope scope, boolean self, String clubName) {
        List<Person> outside = students.stream().filter(student -> !belongs(student.departmentCode(), scope)).toList();
        if (outside.isEmpty()) {
            return;
        }
        String name = clubName == null ? "This club" : clubName;
        String message = self
                ? name + " is only for " + describe(scope) + " students; you are in " + outside.get(0).departmentCode() + "."
                : name + " is only for " + describe(scope) + " students. Not eligible: "
                        + outside.stream().map(student -> student.name() + " (" + student.departmentCode() + ")").collect(Collectors.joining(", ")) + ".";
        throw AppException.forbidden(message, ErrorCode.NOT_ELIGIBLE);
    }
}
