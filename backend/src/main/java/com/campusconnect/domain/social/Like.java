package com.campusconnect.domain.social;

import com.campusconnect.domain.common.BaseDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * A like on an event post, a gallery photo/video or a club story (models/Like.js). Counts are kept on the item
 * itself.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("likes")
@CompoundIndexes({
    @CompoundIndex(
            name = "targetType_1_target_1_user_1",
            def = "{'targetType': 1, 'target': 1, 'user': 1}",
            unique = true),
    @CompoundIndex(name = "user_1_targetType_1_target_1", def = "{'user': 1, 'targetType': 1, 'target': 1}"),
    @CompoundIndex(
            name = "targetType_1_target_1_createdAt_-1",
            def = "{'targetType': 1, 'target': 1, 'createdAt': -1}")
})
public class Like extends BaseDocument {

    private LikeTargetType targetType;

    /** The liked Event, EventMedia or Story. */
    @Field(targetType = FieldType.OBJECT_ID)
    private String target;

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;
}
