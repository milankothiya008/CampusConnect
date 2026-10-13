package com.campusconnect.domain.result;

import com.campusconnect.domain.common.BaseDocument;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/**
 * An issued certificate (models/Certificate.js). Names and titles are copied at issue time, so a certificate still
 * verifies the same way if the event or club is renamed later. Anyone can check one at /verify/&lt;code&gt;.
 */
@Getter
@Setter
@NoArgsConstructor
@Document("certificates")
@CompoundIndexes({
    @CompoundIndex(name = "event_1_user_1_kind_1", def = "{'event': 1, 'user': 1, 'kind': 1}", unique = true),
    @CompoundIndex(name = "user_1_issuedAt_-1", def = "{'user': 1, 'issuedAt': -1}")
})
public class Certificate extends BaseDocument {

    @Indexed(name = "code_1", unique = true)
    private String code;

    @Field(targetType = FieldType.OBJECT_ID)
    private String event;

    @Field(targetType = FieldType.OBJECT_ID)
    private String club;

    @Field(targetType = FieldType.OBJECT_ID)
    private String user;

    private CertificateKind kind;
    private String recipientName;
    private String eventTitle;
    private String clubName;
    private Instant eventStartAt;
    private Instant eventEndAt;
    private String awardTitle;
    private String teamName;
    private Instant issuedAt = Instant.now();

    /** Set if the basis disappears (attendance unmarked, award withdrawn): the code then shows as revoked. */
    private Instant revokedAt;
}
