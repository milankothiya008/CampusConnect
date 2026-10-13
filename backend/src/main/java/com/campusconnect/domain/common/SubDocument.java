package com.campusconnect.domain.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * An embedded document that has its own ObjectId, like a Mongoose sub-schema without {@code _id: false}
 * (form questions, recruitment positions and rounds, budget lines, …). The id is generated on creation and
 * serialised as {@code _id}.
 */
@Getter
@Setter
public abstract class SubDocument {

    @Field(name = "_id", targetType = FieldType.OBJECT_ID)
    @JsonProperty("_id")
    private String id = new ObjectId().toHexString();
}
