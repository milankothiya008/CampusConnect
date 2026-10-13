package com.campusconnect.domain.result;

import com.campusconnect.domain.common.SubDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** An award in an event's final results (EventResult.awards), e.g. "Winner" with its prize. */
@Getter
@Setter
@NoArgsConstructor
public class ResultAward extends SubDocument {

    private String title;

    /** 1 = first place; null when the award has no position. */
    private Integer position;

    private String recipientName;

    @Field(targetType = FieldType.OBJECT_ID)
    private String recipientUser;

    private String teamName;
    private String prize;
    private String recognition;
}
