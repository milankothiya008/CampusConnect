package com.campusconnect.domain.club;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A student club (models/Club.js): open to all departments or a list of them, with per-club roles. */
@Getter
@Setter
@NoArgsConstructor
@Document("clubs")
@CompoundIndex(name = "departmentCodes_1_status_1", def = "{'departmentCodes': 1, 'status': 1}")
public class Club extends BaseDocument {

    @Indexed(name = "name_1", unique = true)
    private String name;

    private String description;
    private String purpose = "";

    /** One of core.util.Categories.CLUB_CATEGORIES. */
    private String category;

    private boolean allDepartments = false;
    private List<String> departmentCodes = new ArrayList<>();

    private String logo;
    private String contactEmail;
    private String tagline = "";
    private String coverImage;
    private String website;
    private String contactPhone;
    private SocialLinks socialLinks = new SocialLinks();
    private String meetingSchedule = "";
    private String meetingLocation = "";

    @Indexed(name = "mentor_1")
    @Field(targetType = FieldType.OBJECT_ID)
    private String mentor;

    @Field(targetType = FieldType.OBJECT_ID)
    private String president;

    /** The club's roles; null for clubs stored before roles were per-club (defaults apply, see ClubRoles). */
    private List<ClubRole> roles;

    @Indexed(name = "status_1")
    private ClubStatus status = ClubStatus.APPROVED;

    /** The admin's reason (suspend/archive) or note (reactivate) for the latest status change. */
    private String statusNote;
    private Instant statusChangedAt;
    /** When the club was suspended or archived; deadlines move on by the paused time on reactivation. */
    private Instant pausedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String creationRequest;
}
