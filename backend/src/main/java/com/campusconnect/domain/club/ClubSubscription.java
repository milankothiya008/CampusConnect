package com.campusconnect.domain.club;

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
 * A user's notification bell for one club (models/ClubSubscription.js). Without a record, members count as
 * subscribed and everyone else as not; a record stores an explicit choice either way.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("clubsubscriptions")
@CompoundIndexes({
    @CompoundIndex(name = "user_1_club_1", def = "{'user': 1, 'club': 1}", unique = true),
    @CompoundIndex(name = "club_1_enabled_1", def = "{'club': 1, 'enabled': 1}")
})
public class ClubSubscription extends BaseDocument {

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    private boolean enabled;
}
