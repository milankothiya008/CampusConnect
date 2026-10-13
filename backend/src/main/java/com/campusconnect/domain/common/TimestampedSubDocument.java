package com.campusconnect.domain.common;

import java.time.Instant;
import lombok.Getter;
import lombok.Setter;

/**
 * A {@link SubDocument} whose Mongoose sub-schema had {@code timestamps: true} (result rounds, recruitment rounds):
 * {@code createdAt}/{@code updatedAt} are set when it is created. Mongo auditing only maintains top-level documents,
 * so a service that changes one of these must call {@link #touch()} (Mongoose did that on save).
 */
@Getter
@Setter
public abstract class TimestampedSubDocument extends SubDocument {

    private Instant createdAt = Instant.now();
    private Instant updatedAt = createdAt;

    /** Marks the sub-document as changed now. */
    public void touch() {
        this.updatedAt = Instant.now();
    }
}
