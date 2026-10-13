package com.campusconnect.feature.chat;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.notify.Notice;
import com.campusconnect.core.notify.NotificationService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.chat.ChatReport;
import com.campusconnect.domain.chat.ChatReportStatus;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.chat.MessageType;
import com.campusconnect.domain.notification.NotificationType;
import com.campusconnect.domain.user.AccountType;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ChatAccess.LoadedMessage;
import com.campusconnect.feature.chat.ChatUsers.UserView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Reported messages (ChatService report / listReports / resolveReport). Reports go to the university admin only; the
 * message text is copied in, so a report survives the sender unsending it.
 */
@Service
public class ChatReports {

    public record ReportResult(boolean reported) {
    }

    /** The sender of a reported message, with their e-mail (only the admin ever sees this). */
    public record SenderView(@JsonProperty("_id") String id, String name, String avatar, AccountType accountType, String departmentCode, String batchCode, String email) {
    }

    public record ReportView(
            @JsonProperty("_id") String id,
            String message,
            String conversation,
            UserView reporter,
            SenderView sender,
            String reason,
            String text,
            int attachmentCount,
            ChatReportStatus status,
            String resolvedBy,
            Instant resolvedAt,
            String note,
            Instant createdAt,
            Instant updatedAt) {
    }

    private final MongoOperations mongo;
    private final ChatAccess access;
    private final ChatUsers users;
    private final NotificationService notifications;

    public ChatReports(MongoOperations mongo, ChatAccess access, ChatUsers users, NotificationService notifications) {
        this.mongo = mongo;
        this.access = access;
        this.users = users;
        this.notifications = notifications;
    }

    public ReportResult report(AuthUser actor, String messageId, String reason) {
        LoadedMessage loaded = access.loadMessage(actor.id(), messageId);
        Message message = loaded.message();
        if (actor.id().equals(message.getSender())) {
            throw AppException.badRequest("You can't report your own message");
        }
        if (message.getType() == MessageType.SYSTEM) {
            throw AppException.badRequest("This can't be reported");
        }
        ChatReport report = new ChatReport();
        report.setMessage(message.getId());
        report.setConversation(loaded.conversation().getId());
        report.setReporter(actor.id());
        report.setSender(message.getSender());
        report.setReason(JsValues.clean(reason, 500));
        report.setText(message.getText());
        report.setAttachmentCount(message.getAttachments().size());
        try {
            mongo.insert(report);
        } catch (DuplicateKeyException alreadyReported) {
            return new ReportResult(true);
        }
        Query admins = Query.query(Criteria.where("globalRole").is(GlobalRole.ADMIN).and("isActive").is(true));
        admins.fields().include("_id");
        List<String> adminIds = mongo.find(admins, User.class).stream().map(User::getId).toList();
        String summary = JsValues.truthy(reason) ? reason : JsValues.text(message.getText());
        notifications.notify(adminIds, Notice.builder()
                .type(NotificationType.CHAT_REPORT)
                .title("A chat message was reported")
                .message(JsValues.cut(summary, 140))
                .link("/admin/chat-reports")
                .build());
        return new ReportResult(true);
    }

    private static void assertAdmin(AuthUser actor) {
        if (actor.globalRole() != GlobalRole.ADMIN) {
            throw AppException.forbidden("Only the university admin can see reports");
        }
    }

    /** Newest 200 reports with the given status (OPEN by default, or ALL). */
    public List<ReportView> listReports(AuthUser actor, String status) {
        assertAdmin(actor);
        String wanted = status == null ? "OPEN" : status;
        Query query = "ALL".equals(wanted) ? new Query() : Query.query(Criteria.where("status").is(ChatReportStatus.valueOf(wanted)));
        query.with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(200);
        List<ChatReport> rows = mongo.find(query, ChatReport.class);
        Map<String, User> people = users.load(rows.stream().flatMap(row -> Stream.of(row.getReporter(), row.getSender())).toList(), "email");
        return rows.stream().map(row -> {
            User sender = row.getSender() == null ? null : people.get(row.getSender());
            UserView senderView = ChatUsers.view(sender);
            return new ReportView(row.getId(), row.getMessage(), row.getConversation(), ChatUsers.view(people.get(row.getReporter())),
                    senderView == null ? null : new SenderView(senderView.id(), senderView.name(), senderView.avatar(), senderView.accountType(),
                            senderView.departmentCode(), senderView.batchCode(), sender.getEmail()),
                    row.getReason(), row.getText(), row.getAttachmentCount(), row.getStatus(), row.getResolvedBy(), row.getResolvedAt(), row.getNote(),
                    row.getCreatedAt(), row.getUpdatedAt());
        }).toList();
    }

    public ChatReport resolveReport(AuthUser actor, String reportId, String note) {
        assertAdmin(actor);
        ChatReport report = null;
        if (ObjectId.isValid(String.valueOf(reportId))) {
            Instant now = JsValues.now();
            report = mongo.findAndModify(ChatAccess.byId(reportId),
                    new Update().set("status", ChatReportStatus.RESOLVED).set("resolvedBy", new ObjectId(actor.id())).set("resolvedAt", now)
                            .set("note", JsValues.clean(note, 500)).set("updatedAt", now),
                    FindAndModifyOptions.options().returnNew(true), ChatReport.class);
        }
        if (report == null) {
            throw AppException.notFound("Report not found");
        }
        return report;
    }
}
