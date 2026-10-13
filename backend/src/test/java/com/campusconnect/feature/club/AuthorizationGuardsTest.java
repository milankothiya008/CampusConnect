package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/authorizationGuards.test.js against {@link ClubAccess}. */
class AuthorizationGuardsTest {

    static AuthUser user(GlobalRole role, AccountType type) {
        return new AuthUser("0123456789abcdef01234567", "Someone", "someone@ddu.ac.in", type, role, true, "CE", null, null, true, null, null);
    }

    static final AuthUser STUDENT = user(GlobalRole.STUDENT, AccountType.STUDENT);
    static final AuthUser FACULTY = user(GlobalRole.FACULTY, AccountType.FACULTY);
    static final AuthUser ADMIN = user(GlobalRole.ADMIN, AccountType.FACULTY);

    @Nested
    class GlobalRoleGuards {

        @Test
        void studentsCannotPerformAdminActions() {
            assertThatThrownBy(() -> ClubAccess.assertAdmin(STUDENT)).isInstanceOf(AppException.class);
        }

        @Test
        void studentsCannotPerformFacultyActions() {
            assertThatThrownBy(() -> ClubAccess.assertFaculty(STUDENT)).isInstanceOf(AppException.class);
        }

        @Test
        void facultyCannotPerformAdminActions() {
            assertThatThrownBy(() -> ClubAccess.assertAdmin(FACULTY)).isInstanceOf(AppException.class);
        }

        @Test
        void adminsPassTheAdminGuard() {
            assertThatCode(() -> ClubAccess.assertAdmin(ADMIN)).doesNotThrowAnyException();
        }

        @Test
        void facultyAndAdminsAreNotTreatedAsStudents() {
            assertThatThrownBy(() -> ClubAccess.assertStudent(FACULTY)).isInstanceOf(AppException.class);
            assertThatThrownBy(() -> ClubAccess.assertStudent(ADMIN)).isInstanceOf(AppException.class);
            assertThatCode(() -> ClubAccess.assertStudent(STUDENT)).doesNotThrowAnyException();
        }
    }
}
