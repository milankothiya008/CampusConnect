package com.campusconnect.domain.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;

/**
 * Common fields of every top-level document: the ObjectId (serialised as {@code _id}, like Mongoose) and the
 * {@code createdAt}/{@code updatedAt} timestamps maintained by Mongo auditing.
 *
 * <p>Auditing only runs on {@code save(...)}. Atomic updates through {@code MongoOperations} must set
 * {@code updatedAt} themselves (see {@code core.util.Updates#touch}). {@code createdAt} is only set when the id is
 * still null at the first save.
 *
 * <p>Index names: every {@code @Indexed}/{@code @CompoundIndex} carries the exact name Mongoose gave the index
 * ({@code club_1_status_1}, {@code createdAt_-1}, or its explicit {@code name}). Spring Data would otherwise name
 * it differently ({@code @Indexed} uses the bare field name), and MongoDB rejects an index whose keys exist under
 * another name (IndexOptionsConflict), so the app would not start on the database the Node app created.
 */
@Getter
@Setter
public abstract class BaseDocument {

    @Id
    @JsonProperty("_id")
    private String id;

    @CreatedDate
    private Instant createdAt;

    @LastModifiedDate
    private Instant updatedAt;
}
