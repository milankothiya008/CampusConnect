package com.campusconnect.feature.chat;

import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.domain.chat.ChatReport;
import com.campusconnect.feature.chat.ChatPeople.BlockResult;
import com.campusconnect.feature.chat.ChatPeople.ChatSettings;
import com.campusconnect.feature.chat.ChatReports.ReportResult;
import com.campusconnect.feature.chat.ChatReports.ReportView;
import com.campusconnect.feature.chat.ChatUploads.LocalUpload;
import com.campusconnect.feature.chat.ChatUsers.UserView;
import com.campusconnect.feature.chat.ConversationService.ClearResult;
import com.campusconnect.feature.chat.ConversationService.ConversationDetail;
import com.campusconnect.feature.chat.ConversationService.MuteResult;
import com.campusconnect.feature.chat.ConversationService.ReadResult;
import com.campusconnect.feature.chat.ConversationService.UnreadSummary;
import com.campusconnect.feature.chat.ConversationViews.ConversationView;
import com.campusconnect.feature.chat.GroupService.LeaveResult;
import com.campusconnect.feature.chat.MessageService.DeleteResult;
import com.campusconnect.feature.chat.MessageService.ForwardResult;
import com.campusconnect.feature.chat.MessageService.MessagePage;
import com.campusconnect.feature.chat.MessageViews.MessageView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * /api/chat (routes/ChatRoutes.js): signed in with a verified e-mail. Live updates arrive over STOMP
 * ({@link ChatRealtimeController}); these are the reads and writes.
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final String OK = "OK";

    private final ConversationService conversations;
    private final GroupService groups;
    private final MessageService messages;
    private final ChatUploads uploads;
    private final ChatPeople people;
    private final ChatReports reports;

    public ChatController(ConversationService conversations, GroupService groups, MessageService messages, ChatUploads uploads, ChatPeople people, ChatReports reports) {
        this.conversations = conversations;
        this.groups = groups;
        this.messages = messages;
        this.uploads = uploads;
        this.people = people;
        this.reports = reports;
    }

    private static String id(String value) {
        return ObjectIds.require(value, "id");
    }

    // ---------------------------------------------------------------- chat list, unread badge, people, settings

    @GetMapping("/conversations")
    public ApiResponse<List<ConversationView>> listConversations(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) @Pattern(regexp = "all|unread|groups|clubs|direct", message = "Invalid value") String filter) {
        return ApiResponse.ok(OK, conversations.listConversations(actor, filter));
    }

    @GetMapping("/unread")
    public ApiResponse<UnreadSummary> unread(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok(OK, conversations.unreadSummary(actor));
    }

    @GetMapping("/people")
    public ApiResponse<List<UserView>> people(@AuthenticationPrincipal AuthUser actor, @RequestParam(required = false) @Size(max = 80, message = "Invalid value") String q) {
        return ApiResponse.ok(OK, people.searchPeople(actor, q));
    }

    @GetMapping("/settings")
    public ApiResponse<ChatSettings> settings(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok(OK, people.getSettings(actor));
    }

    @PutMapping("/settings")
    public ApiResponse<ChatSettings> updateSettings(@AuthenticationPrincipal AuthUser actor, @RequestBody(required = false) ChatRequests.SettingsBody body) {
        return ApiResponse.ok(OK, people.updateSettings(actor, body));
    }

    @GetMapping("/blocks")
    public ApiResponse<List<UserView>> blocks(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok(OK, people.listBlocked(actor));
    }

    @PutMapping("/blocks/{userId}")
    public ApiResponse<BlockResult> setBlocked(@AuthenticationPrincipal AuthUser actor, @PathVariable String userId, @Valid @RequestBody ChatRequests.BlockBody body) {
        return ApiResponse.ok(OK, people.setBlocked(actor, id(userId), body.blocked()));
    }

    // ---------------------------------------------------------------- starting chats

    @PostMapping("/direct")
    public ApiResponse<ConversationDetail> openDirect(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody ChatRequests.DirectBody body) {
        return ApiResponse.ok(OK, conversations.openDirect(actor, body.userId()));
    }

    @PostMapping("/groups")
    public ApiResponse<ConversationDetail> createGroup(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody ChatRequests.GroupBody body) {
        return ApiResponse.ok(OK, conversations.createGroup(actor, body));
    }

    // ---------------------------------------------------------------- one conversation

    @GetMapping("/conversations/{id}")
    public ApiResponse<ConversationDetail> getConversation(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId) {
        return ApiResponse.ok(OK, conversations.getConversation(actor, id(conversationId)));
    }

    @PatchMapping("/conversations/{id}")
    public ApiResponse<ConversationDetail> updateConversation(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId,
            @RequestBody(required = false) Map<String, Object> body) {
        return ApiResponse.ok(OK, groups.updateConversation(actor, id(conversationId), body == null ? Map.of() : body));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<MessagePage> listMessages(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId,
            @RequestParam(required = false) @Pattern(regexp = ChatRequests.OBJECT_ID, message = "Invalid value") String before) {
        return ApiResponse.ok(OK, messages.listMessages(actor, id(conversationId), before));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<MessageView> sendMessage(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId,
            @Valid @RequestBody(required = false) ChatRequests.SendBody body) {
        return ApiResponse.ok("Sent", messages.sendMessage(actor, id(conversationId), body == null ? new ChatRequests.SendBody(null, null, null, null) : body));
    }

    @PostMapping("/conversations/{id}/read")
    public ApiResponse<ReadResult> markRead(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId) {
        return ApiResponse.ok(OK, conversations.markRead(actor, id(conversationId)));
    }

    @PutMapping("/conversations/{id}/mute")
    public ApiResponse<MuteResult> mute(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId,
            @Valid @RequestBody(required = false) ChatRequests.MuteBody body) {
        return ApiResponse.ok(OK, conversations.mute(actor, id(conversationId), body == null ? null : body.duration()));
    }

    @PostMapping("/conversations/{id}/clear")
    public ApiResponse<ClearResult> clear(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId) {
        return ApiResponse.ok(OK, conversations.clearChat(actor, id(conversationId)));
    }

    @PostMapping("/conversations/{id}/leave")
    public ApiResponse<LeaveResult> leave(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId) {
        return ApiResponse.ok(OK, groups.leaveGroup(actor, id(conversationId)));
    }

    @PostMapping("/conversations/{id}/members")
    public ApiResponse<ConversationDetail> addMembers(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId,
            @Valid @RequestBody ChatRequests.AddMembersBody body) {
        return ApiResponse.ok(OK, groups.addMembers(actor, id(conversationId), body.userIds()));
    }

    @DeleteMapping("/conversations/{id}/members/{userId}")
    public ApiResponse<ConversationDetail> removeMember(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId, @PathVariable String userId) {
        return ApiResponse.ok(OK, groups.removeMember(actor, id(conversationId), id(userId)));
    }

    @PutMapping("/conversations/{id}/members/{userId}/admin")
    public ApiResponse<ConversationDetail> setAdmin(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId, @PathVariable String userId,
            @Valid @RequestBody ChatRequests.AdminBody body) {
        return ApiResponse.ok(OK, groups.setAdmin(actor, id(conversationId), id(userId), body.admin()));
    }

    @PutMapping("/conversations/{id}/pins/{messageId}")
    public ApiResponse<ConversationDetail> setPinned(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId, @PathVariable String messageId,
            @Valid @RequestBody ChatRequests.PinBody body) {
        return ApiResponse.ok(OK, messages.setPinned(actor, id(conversationId), id(messageId), body.pinned()));
    }

    @GetMapping("/conversations/{id}/shared")
    public ApiResponse<List<MessageView>> shared(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId,
            @RequestParam(required = false) @Pattern(regexp = "media|links|docs", message = "Invalid value") String kind) {
        return ApiResponse.ok(OK, conversations.sharedItems(actor, id(conversationId), kind == null ? "media" : kind));
    }

    // ---------------------------------------------------------------- uploads

    /** A signed ticket for Cloudinary (or the local endpoint in development); the file is attached on send. */
    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/conversations/{id}/uploads")
    public ApiResponse<MediaStore.UploadTicket> uploadTicket(@AuthenticationPrincipal AuthUser actor, @PathVariable("id") String conversationId,
            @Valid @RequestBody ChatRequests.UploadTicketBody body) {
        return ApiResponse.ok(OK, uploads.uploadTicket(actor, id(conversationId), body.kind(), body.ext()));
    }

    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/media")
    public ApiResponse<LocalUpload> uploadLocal(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) @NotNull(message = "Invalid value") @Pattern(regexp = ChatRequests.OBJECT_ID, message = "Invalid value") String conversation,
            @RequestParam(required = false) @Pattern(regexp = "IMAGE|VIDEO|AUDIO|DOCUMENT", message = "Invalid value") String kind,
            @RequestParam(required = false) @Pattern(regexp = "[0-9A-Za-z]{1,5}", message = "Invalid value") String ext,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ApiResponse.ok(OK, uploads.uploadLocal(actor, conversation, file, kind, ext));
    }

    // ---------------------------------------------------------------- one message

    @PatchMapping("/messages/{messageId}")
    public ApiResponse<MessageView> editMessage(@AuthenticationPrincipal AuthUser actor, @PathVariable String messageId, @Valid @RequestBody ChatRequests.EditBody body) {
        return ApiResponse.ok(OK, messages.editMessage(actor, id(messageId), body.text()));
    }

    @DeleteMapping("/messages/{messageId}")
    public ApiResponse<DeleteResult> deleteMessage(@AuthenticationPrincipal AuthUser actor, @PathVariable String messageId,
            @RequestParam(value = "for", required = false) @Pattern(regexp = "me|everyone", message = "Invalid value") String scope) {
        return ApiResponse.ok(OK, messages.deleteMessage(actor, id(messageId), scope == null ? "me" : scope));
    }

    @PutMapping("/messages/{messageId}/reaction")
    public ApiResponse<MessageView> react(@AuthenticationPrincipal AuthUser actor, @PathVariable String messageId,
            @Valid @RequestBody(required = false) ChatRequests.ReactionBody body) {
        return ApiResponse.ok(OK, messages.react(actor, id(messageId), body == null ? null : body.emoji()));
    }

    @PostMapping("/messages/{messageId}/forward")
    public ApiResponse<ForwardResult> forward(@AuthenticationPrincipal AuthUser actor, @PathVariable String messageId, @Valid @RequestBody ChatRequests.ForwardBody body) {
        return ApiResponse.ok(OK, messages.forward(actor, id(messageId), body.conversationIds()));
    }

    @PostMapping("/messages/{messageId}/report")
    public ApiResponse<ReportResult> report(@AuthenticationPrincipal AuthUser actor, @PathVariable String messageId,
            @Valid @RequestBody(required = false) ChatRequests.ReportBody body) {
        return ApiResponse.ok(OK, reports.report(actor, id(messageId), body == null ? null : body.reason()));
    }

    // ---------------------------------------------------------------- university admin: reported messages

    @GetMapping("/reports")
    public ApiResponse<List<ReportView>> listReports(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) @Pattern(regexp = "OPEN|RESOLVED|ALL", message = "Invalid value") String status) {
        return ApiResponse.ok(OK, reports.listReports(actor, status));
    }

    @PutMapping("/reports/{reportId}")
    public ApiResponse<ChatReport> resolveReport(@AuthenticationPrincipal AuthUser actor, @PathVariable String reportId,
            @Valid @RequestBody(required = false) ChatRequests.ResolveBody body) {
        return ApiResponse.ok(OK, reports.resolveReport(actor, id(reportId), body == null ? null : body.note()));
    }
}
