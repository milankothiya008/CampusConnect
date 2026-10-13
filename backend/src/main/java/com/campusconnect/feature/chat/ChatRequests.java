package com.campusconnect.feature.chat;

import com.campusconnect.core.web.validation.TrimmedSize;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/** Request bodies of /api/chat with the express-validator rules and messages of routes/ChatRoutes.js. */
public final class ChatRequests {

    /** express-validator's isMongoId(). */
    public static final String OBJECT_ID = "^[0-9a-fA-F]{24}$";

    private ChatRequests() {
    }

    public record SettingsBody(Boolean showActivityStatus, Boolean chatNotifications) {
    }

    public record BlockBody(@NotNull(message = "Invalid value") Boolean blocked) {
    }

    public record DirectBody(@NotNull(message = "Choose someone") @Pattern(regexp = OBJECT_ID, message = "Choose someone") String userId) {
    }

    public record GroupBody(
            @NotNull(message = "Name the group") @TrimmedSize(min = 1, max = 80, message = "Name the group") String name,
            @NotNull(message = "Add people to the group") @Size(min = 1, max = 255, message = "Add people to the group")
            List<@NotNull(message = "Invalid value") @Pattern(regexp = OBJECT_ID, message = "Invalid value") String> members) {
    }

    /** Attachments are the upload details the browser got back from the media store (verified on send). */
    public record SendBody(
            @Size(max = 4000, message = "Messages can be up to 4000 characters") String text,
            @Size(max = 10, message = "Up to 10 files at a time") List<Object> attachments,
            @Pattern(regexp = OBJECT_ID, message = "Invalid value") String replyTo,
            @Size(max = 64, message = "Invalid value") String clientId) {
    }

    public record MuteBody(@Pattern(regexp = "15m|1h|8h|24h|1w|always", message = "Invalid value") String duration) {
    }

    public record AddMembersBody(
            @NotNull(message = "Invalid value") @Size(min = 1, max = 100, message = "Invalid value")
            List<@NotNull(message = "Invalid value") @Pattern(regexp = OBJECT_ID, message = "Invalid value") String> userIds) {
    }

    public record AdminBody(@NotNull(message = "Invalid value") Boolean admin) {
    }

    public record PinBody(@NotNull(message = "Invalid value") Boolean pinned) {
    }

    public record UploadTicketBody(
            @NotNull(message = "Unsupported file") @Pattern(regexp = "IMAGE|VIDEO|AUDIO|DOCUMENT", message = "Unsupported file") String kind,
            @Size(max = 5, message = "Invalid value") String ext) {
    }

    public record EditBody(@NotNull(message = "Invalid value") @Size(max = 4000, message = "Invalid value") String text) {
    }

    public record ReactionBody(@Size(max = 32, message = "Invalid value") String emoji) {
    }

    public record ForwardBody(
            @NotNull(message = "Invalid value") @Size(min = 1, max = 5, message = "Invalid value")
            List<@NotNull(message = "Invalid value") @Pattern(regexp = OBJECT_ID, message = "Invalid value") String> conversationIds) {
    }

    public record ReportBody(@Size(max = 500, message = "Invalid value") String reason) {
    }

    public record ResolveBody(@Size(max = 500, message = "Invalid value") String note) {
    }
}
