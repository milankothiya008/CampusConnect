package com.campusconnect.core.util;

import java.time.Instant;
import org.springframework.data.mongodb.core.query.Update;

/** Helpers for atomic updates (Mongoose maintained updatedAt on updateOne; Spring Data does not). */
public final class Updates {

    private Updates() {
    }

    /** Sets {@code updatedAt} to now on an atomic update. */
    public static Update touch(Update update) {
        return update.set("updatedAt", Instant.now());
    }

    public static Update set(String key, Object value) {
        return touch(new Update().set(key, value));
    }
}
