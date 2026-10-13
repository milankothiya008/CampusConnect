package com.campusconnect.domain.result;

import com.campusconnect.domain.common.SubDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * One line of a round's standings (ResultRound.entries): a participant or team, where they placed and whether they
 * go through.
 */
@Getter
@Setter
@NoArgsConstructor
public class ResultEntry extends SubDocument {

    private Integer rank;

    @Field(targetType = FieldType.OBJECT_ID)
    private String recipientUser;

    private String recipientName;
    private String teamName;

    /** Free text, e.g. "34/40". */
    private String score;

    /** true = qualified for the next round, false = eliminated, null = not applicable. */
    private Boolean qualified;

    private String note;
}
