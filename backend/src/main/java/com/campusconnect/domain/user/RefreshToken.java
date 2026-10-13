package com.campusconnect.domain.user;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A refresh token (models/RefreshToken.js). Only the SHA-256 hash is stored; rotation links old to new. */
@Getter
@Setter
@NoArgsConstructor
@Document("refreshtokens")
public class RefreshToken extends BaseDocument {

    @Indexed(name = "user_1")
    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    @Indexed(name = "tokenHash_1", unique = true)
    private String tokenHash;

    /** MongoDB removes expired tokens (TTL index). */
    @Indexed(name = "expiresAt_1", expireAfter = "0s")
    private Instant expiresAt;

    private Instant revokedAt;
    private String replacedByTokenHash;
    private String userAgent;
}
