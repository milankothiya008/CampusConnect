package com.campusconnect.feature.club;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubRole;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/authorizationModel.test.js against {@link ClubRoles}. */
class AuthorizationModelTest {

    static Club club(List<ClubRole> roles) {
        Club club = new Club();
        club.setRoles(roles);
        return club;
    }

    static List<ClubRole> defaultsPlus(ClubRole role) {
        List<ClubRole> roles = new ArrayList<>(ClubRoles.defaultRoles());
        roles.add(role);
        return roles;
    }

    static List<String> names(ClubPermission... permissions) {
        return Arrays.stream(permissions).map(Enum::name).toList();
    }

    @Nested
    class PerClubRoles {

        @Test
        void thePresidentHoldsEveryAuthorityWhateverTheClubsRolesSay() {
            Club custom = club(List.of(new ClubRole("PRESIDENT", "President", "", new ArrayList<>(), true, 0)));
            for (ClubPermission permission : ClubPermission.values()) {
                assertThat(ClubRoles.permissionsFor(custom, "PRESIDENT")).contains(permission.name());
            }
        }

        @Test
        void presidentOnlyAuthoritiesCanNeverComeFromAnotherRole() {
            assertThat(ClubRoles.PRESIDENT_ONLY).contains(ClubPermission.MANAGE_CLUB, ClubPermission.ASSIGN_ROLES, ClubPermission.MANAGE_RECRUITMENT,
                    ClubPermission.PUBLISH_RESULTS, ClubPermission.MANAGE_CHECK_IN);
            Club sneaky = club(defaultsPlus(new ClubRole("R_1", "Sneaky", "", new ArrayList<>(names(ClubPermission.ASSIGN_ROLES, ClubPermission.POST_UPDATES)), false, 50)));
            assertThat(ClubRoles.permissionsFor(sneaky, "R_1")).containsExactly(ClubPermission.POST_UPDATES.name());
            ClubRoles.PRESIDENT_ONLY.forEach(permission -> assertThat(ClubRoles.GRANTABLE).doesNotContain(permission));
        }

        @Test
        void aClubsCustomRolesGrantExactlyWhatThePresidentChose() {
            Club roles = club(defaultsPlus(new ClubRole("R_design", "Design lead", "", new ArrayList<>(names(ClubPermission.POST_UPDATES, ClubPermission.MODERATE_GALLERY)), false, 6)));
            assertThat(ClubRoles.permissionsFor(roles, "R_design")).containsExactly(ClubPermission.POST_UPDATES.name(), ClubPermission.MODERATE_GALLERY.name());
            assertThat(ClubRoles.roleName(roles, "R_design")).isEqualTo("Design lead");
            assertThat(ClubRoles.keysWith(roles, ClubPermission.MODERATE_GALLERY)).contains("PRESIDENT", "VICE_PRESIDENT", "R_design").doesNotContain("MEMBER");
            assertThat(ClubRoles.permissionsFor(roles, "MEMBER")).isEmpty();
            assertThat(ClubRoles.permissionsFor(roles, "R_unknown")).isEmpty();
        }

        @Test
        void clubsStoredBeforeRolesWerePerClubUseTheDefaultRolesKeyedAsBefore() {
            Club legacy = club(null);
            assertThat(ClubRoles.rolesOf(legacy).stream().map(ClubRole::getKey).toList())
                    .containsExactly("PRESIDENT", "VICE_PRESIDENT", "EVENT_COORDINATOR", "MARKETING_COORDINATOR", "TECHNICAL_COORDINATOR", "TREASURER", "MEMBER");
            assertThat(ClubRoles.permissionsFor(legacy, "EVENT_COORDINATOR")).contains(ClubPermission.MANAGE_EVENTS.name(), ClubPermission.MANAGE_PARTICIPANTS.name(),
                    ClubPermission.MARK_ATTENDANCE.name());
            assertThat(ClubRoles.permissionsFor(legacy, "MARKETING_COORDINATOR")).doesNotContain(ClubPermission.VIEW_PARTICIPANTS.name());
            assertThat(ClubRoles.permissionsFor(legacy, "VICE_PRESIDENT")).doesNotContain(ClubPermission.ASSIGN_ROLES.name());
            assertThat(ClubRoles.roleRank(legacy, "PRESIDENT")).isLessThan(ClubRoles.roleRank(legacy, "TREASURER"));
            assertThat(ClubRoles.roleRank(legacy, "TREASURER")).isLessThan(ClubRoles.roleRank(legacy, "MEMBER"));
        }
    }
}
