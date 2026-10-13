package com.campusconnect.feature.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.util.CampusTime;
import com.campusconnect.feature.auth.UniversityRules;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Port of tests/unit/universityRules.test.js: e-mail parsing ({@link UniversityRules}) and the date helpers, which the
 * Java backend keeps in {@link CampusTime}.
 */
class UniversityRulesTest {

    static final UniversityRules RULES = new UniversityRules(new AppProperties(), null);

    @Nested
    class UniversityEmailParsing {

        @Test
        void parsesStudentEmailsBatchDepartmentThreeLetterIdentityThreeDigitNumber() {
            assertThat(RULES.parseStudentEmail("24ceuog001@ddu.ac.in")).isEqualTo(new UniversityRules.StudentParts("24", "CE", "UOG001"));
        }

        @Test
        void supportsTwoToFourLetterDepartmentCodes() {
            assertThat(RULES.parseStudentEmail("24CSEUOG015@ddu.ac.in").departmentCode()).isEqualTo("CSE");
            assertThat(RULES.parseStudentEmail("23MECHABC999@ddu.ac.in").departmentCode()).isEqualTo("MECH");
        }

        @ParameterizedTest(name = "rejects malformed student email {0}")
        @ValueSource(strings = {"24ce1234", "24ceuog01", "24ceuog0012", "24ceu0g001", "2ceuog001", "24cuog001", "24ceuog001x"})
        void rejectsMalformedStudentEmails(String local) {
            assertThat(RULES.parseStudentEmail(local + "@ddu.ac.in")).isNull();
        }

        @Test
        void parsesFacultyEmailsFirstNameDotDepartment() {
            assertThat(RULES.parseFacultyEmail("brij.ce@ddu.ac.in")).isEqualTo(new UniversityRules.FacultyParts("CE", "brij"));
            assertThat(RULES.parseStudentEmail("brij.ce@ddu.ac.in")).isNull();
        }

        @ParameterizedTest(name = "rejects malformed faculty email {0}")
        @ValueSource(strings = {"brij.patel.ce", "brij1.ce", "brij.c", "brij.cehme", ".ce", "brij"})
        void rejectsMalformedFacultyEmails(String local) {
            assertThat(RULES.parseFacultyEmail(local + "@ddu.ac.in")).isNull();
        }

        @Test
        void formatCheckAlsoRequiresTheUniversityDomain() {
            assertThat(RULES.isValidUniversityEmailFormat("24ceuog001@ddu.ac.in")).isTrue();
            assertThat(RULES.isValidUniversityEmailFormat("brij.ce@ddu.ac.in")).isTrue();
            assertThat(RULES.isValidUniversityEmailFormat("24ceuog001@gmail.com")).isFalse();
        }
    }

    @Nested
    class VenueIntervalOverlap {

        Instant at(int hour) {
            return LocalDateTime.of(2026, 8, 30, hour, 0).atZone(ZoneId.systemDefault()).toInstant();
        }

        @Test
        void detectsOverlappingIntervals() {
            assertThat(CampusTime.intervalsOverlap(at(10), at(12), at(11), at(13))).isTrue();
        }

        @Test
        void allowsBackToBackIntervals() {
            assertThat(CampusTime.intervalsOverlap(at(10), at(12), at(12), at(14))).isFalse();
        }

        @Test
        void detectsContainedIntervals() {
            assertThat(CampusTime.intervalsOverlap(at(10), at(14), at(11), at(12))).isTrue();
        }
    }

    @Nested
    class UniversityLocalDateAndTime {

        @Test
        void combinesADateAndHhMmInTheUniversityTimezone() {
            CampusTime.configure("+05:30");
            assertThat(CampusTime.combineDateAndTime("2026-10-05", "10:00")).isEqualTo(Instant.parse("2026-10-05T04:30:00Z"));
        }

        @Test
        void usesTheCalendarDateOfAStoredUtcMidnightDate() {
            assertThat(CampusTime.toDateKey(Instant.parse("2026-10-05T00:00:00Z"))).isEqualTo("2026-10-05");
        }

        @Test
        void rejectsMalformedTimes() {
            assertThatThrownBy(() -> CampusTime.combineDateAndTime("2026-10-05", "25:00")).isInstanceOf(RuntimeException.class);
        }
    }
}
