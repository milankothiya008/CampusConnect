package com.campusconnect.core.audit;

import com.campusconnect.domain.audit.AuditAction;
import com.campusconnect.domain.audit.AuditLog;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Service;

/** Records who did what to which record (services/AuditService.js). */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final MongoOperations mongo;

    public void record(AuditAction action, String actorId, String targetType, String targetId) {
        record(action, actorId, targetType, targetId, null, null, null, Map.of());
    }

    public void record(AuditAction action, String actorId, String targetType, String targetId, Map<String, Object> metadata) {
        record(action, actorId, targetType, targetId, null, null, null, metadata);
    }

    public void record(AuditAction action, String actorId, String targetType, String targetId, String fromState, String toState, String reason, Map<String, Object> metadata) {
        AuditLog entry = new AuditLog();
        entry.setAction(action);
        entry.setActor(actorId);
        entry.setTargetType(targetType);
        entry.setTargetId(targetId);
        entry.setFromState(fromState);
        entry.setToState(toState);
        entry.setReason(reason);
        entry.setMetadata(metadata == null ? new LinkedHashMap<>() : new LinkedHashMap<>(metadata));
        try {
            mongo.insert(entry);
            log.info("{} {} {} {} -> {}", action, targetType, targetId, fromState, toState);
        } catch (RuntimeException e) {
            // The audit trail is a side effect: it must never undo the action itself.
            log.error("Failed to record audit entry {} {} {}: {}", action, targetType, targetId, e.getMessage());
        }
    }
}
