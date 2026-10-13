package com.campusconnect.feature.chat;

import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.push.PushPayload;
import com.campusconnect.core.push.PushService;
import com.campusconnect.core.realtime.PresenceRegistry;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationMember;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.chat.LastMessage;
import com.campusconnect.domain.chat.LinkPreview;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.chat.MessageAttachment;
import com.campusconnect.domain.chat.MessageType;
import com.campusconnect.domain.chat.SystemAction;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ChatAccess.Access;
import com.campusconnect.feature.chat.ConversationViews.ConversationView;
import com.campusconnect.feature.chat.MessageViews.MessageView;
import com.campusconnect.feature.chat.link.LinkPreviewService;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Getting messages to people: storing them, keeping the chat list's "last message" current, the live
 * {@code message:new} / {@code message:updated} events (through {@link RealtimeGateway}, which skips anyone offline),
 * phone notifications and the link preview filled in after sending.
 */
@Slf4j
@Component
public class ChatDelivery {

    private final MongoOperations mongo;
    private final ChatAccess access;
    private final MessageViews messageViews;
    private final ConversationViews conversationViews;
    private final RealtimeGateway gateway;
    private final PresenceRegistry presence;
    private final PushService push;
    private final LinkPreviewService links;
    private final ChatBackground background;
    private final MediaStores media;

    public ChatDelivery(MongoOperations mongo, ChatAccess access, MessageViews messageViews, ConversationViews conversationViews, RealtimeGateway gateway,
            PresenceRegistry presence, PushService push, LinkPreviewService links, ChatBackground background, MediaStores media) {
        this.mongo = mongo;
        this.access = access;
        this.messageViews = messageViews;
        this.conversationViews = conversationViews;
        this.gateway = gateway;
        this.presence = presence;
        this.push = push;
        this.links = links;
        this.background = background;
        this.media = media;
    }

    /** Stores a new message; its timestamps are kept at the precision MongoDB stores. */
    public Message insert(Message message) {
        mongo.insert(message);
        message.setCreatedAt(JsValues.millis(message.getCreatedAt()));
        message.setUpdatedAt(JsValues.millis(message.getUpdatedAt()));
        return message;
    }

    /**
     * Makes this message the chat's latest. With a {@code readerId} (the sender) their read marker moves to now in the
     * same write, so sending never shows your own message as unread.
     */
    public void touch(Conversation conversation, Message message, String readerId) {
        LastMessage last = new LastMessage();
        last.setMessage(message.getId());
        last.setSender(message.getSender());
        last.setPreview(MessageViews.previewOf(message));
        last.setSystem(message.getType() == MessageType.SYSTEM);
        last.setAt(message.getCreatedAt());
        Instant now = JsValues.now();
        Update update = new Update().set("lastMessage", last).set("lastMessageAt", message.getCreatedAt()).set("updatedAt", now);
        if (readerId != null) {
            update.set("members.$[reader].lastReadAt", now).filterArray(Criteria.where("reader.user").is(new ObjectId(readerId)));
        }
        mongo.updateFirst(ChatAccess.byId(conversation.getId()), update, Conversation.class);
        conversation.setLastMessage(last);
        conversation.setLastMessageAt(message.getCreatedAt());
        if (readerId != null) {
            ConversationMember reader = ChatAccess.memberOf(conversation, readerId);
            if (reader != null) {
                reader.setLastReadAt(now);
            }
        }
    }

    /** A grey line in the chat ("Asha added Bina"), from a person or (actorId null) from CampusConnect. */
    public Message system(Conversation conversation, String actorId, String actorName, String text, String action) {
        Message message = new Message();
        message.setConversation(conversation.getId());
        message.setSender(actorId);
        message.setType(MessageType.SYSTEM);
        message.setText(text);
        message.setSystem(new SystemAction(action, actorName, null));
        insert(message);
        touch(conversation, message, null);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversation.getId());
        payload.put("message", messageViews.serializeOne(message, ""));
        gateway.emitToUsers(access.recipientIds(conversation), "message:new", payload);
        return message;
    }

    /** People who muted this chat (a mute lasts until the chosen time). */
    public static List<String> mutedIn(Conversation conversation) {
        Instant now = Instant.now();
        return conversation.getMembers().stream()
                .filter(entry -> entry.getMutedUntil() != null && entry.getMutedUntil().isAfter(now))
                .map(ConversationMember::getUser)
                .toList();
    }

    /** {@code message:new} as every member receives it: no "mine", the chat's header and who muted it. */
    public static Map<String, Object> newMessagePayload(String conversationId, MessageView view, Map<String, Object> header, List<String> mutedFor) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("message", view.withoutMine());
        payload.put("conversation", header);
        // Each tab hides the in-app banner when its person muted this chat.
        payload.put("mutedFor", mutedFor);
        return payload;
    }

    /** Sends a member's new message to everyone in the chat (live and by push); returns the sender's own view of it. */
    public MessageView announce(Conversation conversation, Access senderAccess, Message message, AuthUser sender, List<String> recipients) {
        MessageView view = messageViews.serializeOne(message, sender.id());
        ConversationView row = conversationViews.row(conversation, sender.id(), senderAccess, null, null, 0);
        gateway.emitToUsers(recipients, "message:new", newMessagePayload(conversation.getId(), view, row.header(), mutedIn(conversation)));
        notifyPush(conversation, message, sender, recipients, row);
        return view;
    }

    /**
     * Phone / computer notifications for a new message, like Instagram: "Asha" — "See you at 5", or for groups
     * "Coding Club" — "Asha: See you at 5". Everyone in the chat gets one, except whoever sent it, people who have
     * this chat open on screen, people who muted it and people who switched message notifications off.
     */
    void notifyPush(Conversation conversation, Message message, AuthUser sender, List<String> recipients, ConversationView row) {
        try {
            Set<String> skip = new HashSet<>(mutedIn(conversation));
            skip.add(sender.id());
            skip.addAll(presence.viewersOf(conversation.getId()));
            List<String> candidates = recipients.stream().filter(id -> !skip.contains(id)).toList();
            if (candidates.isEmpty()) {
                return;
            }
            Query optedOutQuery = Query.query(Criteria.where("_id").in(candidates.stream().map(ObjectId::new).toList()).and("chatNotifications").is(false));
            optedOutQuery.fields().include("_id");
            Set<String> optedOut = mongo.find(optedOutQuery, User.class).stream().map(User::getId).collect(Collectors.toSet());
            List<String> targets = candidates.stream().filter(id -> !optedOut.contains(id)).toList();
            if (targets.isEmpty()) {
                return;
            }
            boolean direct = conversation.getType() == ConversationType.DIRECT;
            String preview = MessageViews.previewOf(message);
            String title = direct ? sender.name() : row.title();
            String body = direct ? preview : sender.name() + ": " + preview;
            String icon = sender.avatar() != null && sender.avatar().startsWith("https://") ? sender.avatar() : null;
            push.pushToUsers(targets, new PushPayload(title, JsValues.cut(body, 140), "/messages/" + conversation.getId(), "chat-" + conversation.getId(), icon, "chat",
                    conversation.getId()));
        } catch (RuntimeException e) {
            log.debug("Chat push failed: {}", e.getMessage());
        }
    }

    /** {@code message:updated} to everyone in the chat. */
    public void broadcastUpdate(Conversation conversation, String messageId, Map<String, Object> patch) {
        gateway.emitToUsers(access.recipientIds(conversation), "message:updated", updatePayload(conversation.getId(), messageId, patch));
    }

    private static Map<String, Object> updatePayload(String conversationId, String messageId, Map<String, Object> patch) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("messageId", messageId);
        payload.put("patch", patch);
        return payload;
    }

    /** Fetches a preview card for the first link of the text in the background, then shows it to everyone. */
    public void previewLinkLater(Message message, List<String> recipients) {
        String url = LinkPreviewService.firstLink(message.getText());
        if (url == null) {
            return;
        }
        String messageId = message.getId();
        String conversationId = message.getConversation();
        background.run("Link preview", () -> {
            LinkPreview preview = links.previewFor(url);
            if (preview == null) {
                return;
            }
            long attached = mongo.updateFirst(Query.query(Criteria.where("_id").is(new ObjectId(messageId)).and("deletedAt").is(null)),
                    new Update().set("link", preview).set("updatedAt", JsValues.now()), Message.class).getModifiedCount();
            if (attached > 0) {
                gateway.emitToUsers(recipients, "message:updated", updatePayload(conversationId, messageId, Map.of("link", preview)));
            }
        });
    }

    /** Removes stored files no other message still uses (a forwarded copy keeps them). */
    public void deleteFilesLater(String messageId, List<MessageAttachment> files) {
        if (files.isEmpty()) {
            return;
        }
        background.run("Chat file cleanup", () -> {
            for (MessageAttachment file : files) {
                boolean elsewhere = mongo.exists(Query.query(Criteria.where("_id").ne(new ObjectId(messageId)).and("attachments.key").is(file.getKey())), Message.class);
                if (!elsewhere) {
                    media.chat().deleteMediaQuietly(file);
                }
            }
        });
    }
}
