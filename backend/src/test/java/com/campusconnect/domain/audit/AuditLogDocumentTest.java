package com.campusconnect.domain.audit;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.domain.MongoDocumentTest;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

class AuditLogDocumentTest extends MongoDocumentTest {

    @Autowired
    AuditLogRepository auditLogs;

    @Test
    void auditLogRoundTrip() {
        AuditLog log = new AuditLog();
        log.setAction(AuditAction.ELECTION_CLOSED);
        log.setActor(newId());
        log.setTargetType("ClubElection");
        log.setTargetId(newId());
        log.setFromState("OPEN");
        log.setToState("CLOSED");
        log.setReason("Voting ended");
        log.getMetadata().put("votesCast", 12);
        log.getMetadata().put("winners", List.of("Asha"));
        auditLogs.save(log);

        assertSameDocument(auditLogs.findById(log.getId()).orElseThrow(), log);
        Document raw = raw("auditlogs");
        assertObjectIds(raw, "_id", "actor", "targetId");
        assertThat(raw.getString("action")).isEqualTo("ELECTION_CLOSED");
        assertThat(valueAt(raw, "metadata.votesCast")).isEqualTo(12);
        JsonNode json = toJson(log);
        assertThat(json.get("_id").stringValue()).isEqualTo(log.getId());
        assertThat(json.get("metadata").get("winners").get(0).stringValue()).isEqualTo("Asha");
        assertThat(json.get("targetId").stringValue()).isEqualTo(log.getTargetId());
    }

    @Test
    void emptyMetadataIsStoredAsAnEmptyDocument() {
        AuditLog log = new AuditLog();
        log.setAction(AuditAction.USER_REGISTERED);
        log.setActor(newId());
        log.setTargetType("User");
        log.setTargetId(log.getActor());
        auditLogs.save(log);

        assertThat(raw("auditlogs").get("metadata", Document.class)).isEmpty();
        assertAbsent(raw("auditlogs"), "fromState", "reason");
        assertThat(toJson(log).get("metadata").isObject()).isTrue();
    }
}
