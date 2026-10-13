package com.campusconnect.feature.chat;

import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.domain.chat.LinkPreview;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.chat.MessageAttachment;
import com.campusconnect.domain.chat.MessageReaction;
import com.campusconnect.domain.chat.MessageType;
import com.campusconnect.domain.chat.SystemAction;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.common.StoredMedia;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ChatUsers.UserView;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Messages as the chat screen shows them (ChatService serializeMessages / attachmentView / previewOf): the sender,
 * the message replied to as a short quote, reactions grouped by emoji and viewer-facing file URLs. A page of
 * messages costs two queries whatever its size (replies, then every person involved).
 */
@Component
public class MessageViews {

    public record AttachmentView(MediaKind kind, String url, String thumb, String poster, String name, String format, Long bytes, Integer width, Integer height, Double duration) {
    }

    public record ReplyView(@JsonProperty("_id") String id, UserView sender, String text, String thumb) {
    }

    /** {@code mine} is left out of what is broadcast to everyone. */
    public record ReactionView(String emoji, List<String> users, int count, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean mine) {
    }

    /** One message; {@code mine} is left out of the copies sent to every member (each works it out from the sender). */
    public record MessageView(
            @JsonProperty("_id") String id,
            String conversation,
            String clientId,
            MessageType type,
            UserView sender,
            @JsonInclude(JsonInclude.Include.NON_NULL) Boolean mine,
            String text,
            List<AttachmentView> attachments,
            LinkPreview link,
            ReplyView replyTo,
            boolean forwarded,
            List<ReactionView> reactions,
            Instant editedAt,
            boolean deleted,
            Map<String, Object> system,
            String poll,
            Instant createdAt) {

        public MessageView withoutMine() {
            return new MessageView(id, conversation, clientId, type, sender, null, text, attachments, link, replyTo, forwarded, reactions, editedAt, deleted, system, poll, createdAt);
        }

        /** The reactions as broadcast to everyone: who reacted, without "mine". */
        public List<ReactionView> publicReactions() {
            return reactions.stream().map(reaction -> new ReactionView(reaction.emoji(), reaction.users(), reaction.count(), null)).toList();
        }
    }

    static final String BALLOT = "🗳️";
    private static final Map<MediaKind, String[]> KIND_LABEL = Map.of(
            MediaKind.IMAGE, new String[] {"📷 Photo", "photos"},
            MediaKind.VIDEO, new String[] {"🎥 Video", "videos"},
            MediaKind.AUDIO, new String[] {"🎤 Voice message", "voice messages"},
            MediaKind.DOCUMENT, new String[] {"📄 File", "files"});

    private final MongoOperations mongo;
    private final ChatUsers users;
    private final MediaStore store;

    public MessageViews(MongoOperations mongo, ChatUsers users, MediaStores stores) {
        this.mongo = mongo;
        this.users = users;
        this.store = stores.chat();
    }

    /** The one-line preview of a message in the chat list, notifications and reply quotes. */
    public static String previewOf(Message message) {
        if (message.getDeletedAt() != null) {
            return "Message unsent";
        }
        String text = message.getText() == null ? "" : message.getText();
        if (message.getType() == MessageType.SYSTEM) {
            return text;
        }
        if (message.getType() == MessageType.POLL) {
            return JsValues.cut(BALLOT + " " + text, 120);
        }
        if (!text.isEmpty()) {
            return JsValues.cut(text, 120);
        }
        List<MessageAttachment> files = message.getAttachments() == null ? List.of() : message.getAttachments();
        if (files.isEmpty()) {
            return "";
        }
        MessageAttachment first = files.get(0);
        if (first.getKind() == MediaKind.DOCUMENT) {
            return "📄 " + (JsValues.truthy(first.getName()) ? first.getName() : "File");
        }
        String[] label = KIND_LABEL.get(first.getKind());
        return files.size() > 1 ? label[0].split(" ")[0] + " " + files.size() + " " + label[1] : label[0];
    }

    public AttachmentView attachmentView(MessageAttachment attachment) {
        MediaStore.MediaUrls urls = store.mediaUrls(attachment);
        return new AttachmentView(attachment.getKind(), urls.url(), urls.thumb(), urls.poster(), JsValues.truthy(attachment.getName()) ? attachment.getName() : "",
                attachment.getFormat(), attachment.getBytes(), attachment.getWidth(), attachment.getHeight(), attachment.getDuration());
    }

    /** A group photo's thumbnail (stored as an image in the chat media store). */
    public String groupAvatar(StoredMedia avatar) {
        return avatar == null ? null : store.mediaUrls(MediaKind.IMAGE, avatar.getProvider(), avatar.getKey(), avatar.getVersion()).thumb();
    }

    /** Messages for the screen of this viewer ({@code ""} for "nobody in particular", as the original passed {@code {_id: null}}). */
    public List<MessageView> serialize(List<Message> messages, String viewerId) {
        if (messages.isEmpty()) {
            return List.of();
        }
        List<ObjectId> replyIds = messages.stream().map(Message::getReplyTo).filter(Objects::nonNull).distinct().map(ObjectId::new).toList();
        Map<String, Message> replies = new LinkedHashMap<>();
        if (!replyIds.isEmpty()) {
            Query query = Query.query(Criteria.where("_id").in(replyIds));
            query.fields().include("sender").include("text").include("attachments").include("type").include("deletedAt");
            mongo.find(query, Message.class).forEach(reply -> replies.put(reply.getId(), reply));
        }
        Map<String, User> people = users.load(Stream.concat(messages.stream(), replies.values().stream()).map(Message::getSender).filter(Objects::nonNull).toList());
        String me = viewerId == null ? "" : viewerId;
        List<MessageView> views = new ArrayList<>(messages.size());
        for (Message message : messages) {
            views.add(view(message, message.getReplyTo() == null ? null : replies.get(message.getReplyTo()), people, me));
        }
        return views;
    }

    public MessageView serializeOne(Message message, String viewerId) {
        return serialize(List.of(message), viewerId).get(0);
    }

    private MessageView view(Message message, Message reply, Map<String, User> people, String me) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (MessageReaction reaction : message.getReactions()) {
            grouped.computeIfAbsent(reaction.getEmoji(), emoji -> new ArrayList<>()).add(reaction.getUser());
        }
        List<ReactionView> reactions = grouped.entrySet().stream()
                .map(group -> new ReactionView(group.getKey(), group.getValue(), group.getValue().size(), group.getValue().contains(me)))
                .toList();
        boolean deleted = message.getDeletedAt() != null;
        String sender = message.getSender() == null ? "" : message.getSender();
        return new MessageView(
                message.getId(),
                message.getConversation(),
                JsValues.truthy(message.getClientId()) ? message.getClientId() : null,
                message.getType(),
                message.getSender() == null ? null : ChatUsers.view(people.get(message.getSender())),
                sender.equals(me),
                deleted ? "" : message.getText(),
                deleted ? List.of() : message.getAttachments().stream().map(this::attachmentView).toList(),
                deleted ? null : message.getLink(),
                reply == null ? null : replyView(reply, people),
                message.isForwarded(),
                reactions,
                message.getEditedAt(),
                deleted,
                systemView(message.getSystem()),
                message.getPoll(),
                message.getCreatedAt());
    }

    private ReplyView replyView(Message reply, Map<String, User> people) {
        boolean deleted = reply.getDeletedAt() != null;
        MessageAttachment first = reply.getAttachments() == null || reply.getAttachments().isEmpty() ? null : reply.getAttachments().get(0);
        String thumb = !deleted && first != null && (first.getKind() == MediaKind.IMAGE || first.getKind() == MediaKind.VIDEO) ? attachmentView(first).thumb() : null;
        return new ReplyView(reply.getId(), reply.getSender() == null ? null : ChatUsers.view(people.get(reply.getSender())), deleted ? "Message unsent" : previewOf(reply), thumb);
    }

    /** {@code { action, actor, target? }}: only what was recorded, like the stored sub-document. */
    private static Map<String, Object> systemView(SystemAction system) {
        if (system == null) {
            return null;
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("action", system.getAction());
        view.put("actor", system.getActor());
        if (system.getTarget() != null) {
            view.put("target", system.getTarget());
        }
        return view;
    }
}
