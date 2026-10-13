package com.campusconnect.domain.event;

import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** Check-in at the door (Event.checkIn; see CheckInService): the president opens it, officers scan tickets. */
@Getter
@Setter
@NoArgsConstructor
public class EventCheckIn {

    private CheckInStatus status = CheckInStatus.NOT_STARTED;
    private Instant openedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String openedBy;

    private Instant closedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String closedBy;
}
