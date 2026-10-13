package com.campusconnect.domain.event;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** What was actually spent, recorded by the club once the event has started (Event.expenses). */
@Getter
@Setter
@NoArgsConstructor
public class EventExpenses {

    private List<Expense> items = new ArrayList<>();
    private String note = "";
    private Instant submittedAt;

    @Field(targetType = FieldType.OBJECT_ID)
    private String submittedBy;
}
