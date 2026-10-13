package com.campusconnect.feature.chat;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.chat.MessageAttachment;
import com.campusconnect.domain.chat.MessageReaction;
import com.campusconnect.domain.chat.MessageType;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.feature.chat.ChatAccess.Loaded;
import com.campusconnect.feature.chat.ChatAccess.LoadedMessage;
import com.campusconnect.feature.chat.ConversationService.ConversationDetail;
import com.campusconnect.feature.chat.MessageViews.MessageView;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.bson.Document;
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
 * Messages: reading a chat page by page, sending (text, files, voice notes, replies), editing, unsending, reactions,
 * forwarding and pins (ChatService listMessages / sendMessage / editMessage / deleteMessage / react / forward /
 * setPinned).
 */
@Service
public class MessageService {

    static final int PAGE = 40;
    static final int MAX_PINS = 3;
    /** One to ten emoji (with joiners, variation selectors and skin tones), like the original's Unicode regex. */
    static final Pattern EMOJI = Pattern.compile("^(\\p{IsExtended_Pictographic}|\\p{IsEmoji_Presentation}|‍|️|\\p{IsEmoji_Modifier}){1,10}$");

    public record MessagePage(List<MessageView> items, boolean hasMore) {
    }

    public record DeleteResult(String deleted) {
    }

    public record ForwardResult(List<String> forwardedTo) {
    }

    private final MongoOperations mongo;
    private final ChatAccess access;
    private final MessageViews views;
    private final ChatDelivery delivery;
    private final SendRateLimit rateLimit;
    private final ConversationService conversations;
    private final RealtimeGateway gateway;
    private final MediaStore store;
    private final AppProperties properties;

    public MessageService(MongoOperations mongo, ChatAccess access, MessageViews views, ChatDelivery delivery, SendRateLimit rateLimit, ConversationService conversations,
            RealtimeGateway gateway, MediaStores stores, AppProperties properties) {
        this.mongo = mongo;
        this.access = access;
        this.views = views;
        this.delivery = delivery;
        this.rateLimit = rateLimit;
        this.conversations = conversations;
        this.gateway = gateway;
        this.store = stores.chat();
        this.properties = properties;
    }

    /** The 40 messages before {@code before} (or the latest), oldest first, within the viewer's visible history. */
    public MessagePage listMessages(AuthUser actor, String conversationId, String before) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Criteria filter = Criteria.where("conversation").is(new ObjectId(loaded.conversation().getId())).and("hiddenFor").ne(new ObjectId(actor.id()));
        Criteria createdAt = filter.and("createdAt").gte(loaded.access().visibleFrom());
        if (before != null && ObjectId.isValid(before)) {
            Query anchorQuery = ChatAccess.byId(before);
            anchorQuery.fields().include("createdAt");
            Message anchor = mongo.findOne(anchorQuery, Message.class);
            if (anchor != null) {
                createdAt.lt(anchor.getCreatedAt());
            }
        }
        Query query = Query.query(filter).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(PAGE + 1);
        List<Message> rows = new ArrayList<>(mongo.find(query, Message.class));
        boolean hasMore = rows.size() > PAGE;
        List<Message> page = new ArrayList<>(rows.subList(0, Math.min(PAGE, rows.size())));
        Collections.reverse(page);
        return new MessagePage(views.serialize(page, actor.id()), hasMore);
    }

    private static MessageType typeOf(List<MessageAttachment> attachments) {
        if (attachments.isEmpty()) {
            return MessageType.TEXT;
        }
        return switch (attachments.get(0).getKind()) {
            case AUDIO -> MessageType.VOICE;
            case DOCUMENT -> MessageType.FILE;
            default -> MessageType.MEDIA;
        };
    }

    @SuppressWarnings("unchecked")
    private MessageAttachment verifiedAttachment(Object file, String conversationId, String userId) {
        Map<String, Object> details = file instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
        MediaStore.VerifiedMedia verified = store.verifyUpload(details, conversationId, userId);
        MessageAttachment attachment = new MessageAttachment();
        attachment.setKind(verified.kind());
        attachment.setProvider(verified.provider());
        attachment.setKey(verified.key());
        attachment.setVersion(verified.version());
        attachment.setFormat(verified.format());
        attachment.setWidth(verified.width());
        attachment.setHeight(verified.height());
        attachment.setDuration(verified.duration());
        attachment.setBytes(verified.bytes());
        attachment.setName(JsValues.cut(JsValues.text(details.get("name")), 160));
        return attachment;
    }

    private Message existingResend(String conversationId, String senderId, String clientId) {
        return mongo.findOne(Query.query(Criteria.where("conversation").is(new ObjectId(conversationId))
                .and("sender").is(new ObjectId(senderId)).and("clientId").is(clientId)), Message.class);
    }

    public MessageView sendMessage(AuthUser actor, String conversationId, ChatRequests.SendBody body) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Conversation conversation = loaded.conversation();
        String blocked = access.sendBlockedReason(actor.id(), conversation, loaded.access());
        if (blocked != null) {
            throw AppException.forbidden(blocked);
        }
        String text = JsValues.clean(body.text(), 4000);
        List<Object> files = body.attachments() == null ? List.of() : body.attachments().subList(0, Math.min(10, body.attachments().size()));
        if (text.isEmpty() && files.isEmpty()) {
            throw AppException.badRequest("Write a message or add a file");
        }
        String clientId = JsValues.truthy(body.clientId()) ? JsValues.cut(body.clientId(), 64) : null;
        if (clientId != null) {
            // A resend after a dropped connection isn't stored twice.
            Message existing = existingResend(conversation.getId(), actor.id(), clientId);
            if (existing != null) {
                return views.serializeOne(existing, actor.id());
            }
        }
        rateLimit.check(actor.id());

        List<MessageAttachment> verified = files.stream().map(file -> verifiedAttachment(file, conversation.getId(), actor.id())).toList();
        if (verified.stream().anyMatch(file -> file.getKind() == MediaKind.AUDIO) && verified.size() > 1) {
            throw AppException.badRequest("Send a voice message on its own");
        }
        String replyTo = null;
        if (JsValues.truthy(body.replyTo())) {
            Message reply = null;
            if (ObjectId.isValid(body.replyTo())) {
                Query replyQuery = Query.query(Criteria.where("_id").is(new ObjectId(body.replyTo()))
                        .and("conversation").is(new ObjectId(conversation.getId()))
                        .and("createdAt").gte(loaded.access().visibleFrom()));
                replyQuery.fields().include("_id");
                reply = mongo.findOne(replyQuery, Message.class);
            }
            if (reply == null) {
                throw AppException.badRequest("The message you're replying to isn't in this chat");
            }
            replyTo = reply.getId();
        }

        Message message = new Message();
        message.setConversation(conversation.getId());
        message.setSender(actor.id());
        message.setType(typeOf(verified));
        message.setText(text);
        message.setAttachments(new ArrayList<>(verified));
        message.setReplyTo(replyTo);
        message.setClientId(clientId);
        try {
            delivery.insert(message);
        } catch (DuplicateKeyException sameResendAtOnce) {
            Message existing = existingResend(conversation.getId(), actor.id(), clientId);
            if (existing == null) {
                throw sameResendAtOnce;
            }
            return views.serializeOne(existing, actor.id());
        }
        delivery.touch(conversation, message, actor.id());
        List<String> recipients = access.recipientIds(conversation);
        MessageView view = delivery.announce(conversation, loaded.access(), message, actor, recipients);
        if (!text.isEmpty() && verified.isEmpty()) {
            delivery.previewLinkLater(message, recipients);
        }
        return view;
    }

    // ---------------------------------------------------------------- editing, unsending, reactions, forwarding

    public MessageView editMessage(AuthUser actor, String messageId, String text) {
        LoadedMessage loaded = access.loadMessage(actor.id(), messageId);
        Message message = loaded.message();
        if (!actor.id().equals(message.getSender()) || message.getType() == MessageType.SYSTEM) {
            throw AppException.forbidden("You can only edit your own messages");
        }
        if (message.getType() == MessageType.POLL) {
            throw AppException.badRequest("Edit the election itself instead");
        }
        if (message.getDeletedAt() != null) {
            throw AppException.badRequest("This message was unsent");
        }
        int editMinutes = properties.getChat().getEditMinutes();
        if (Duration.between(message.getCreatedAt(), Instant.now()).toMillis() > editMinutes * 60_000L) {
            throw AppException.badRequest("Messages can be edited for " + editMinutes + " minutes after sending");
        }
        String body = JsValues.clean(text, 4000);
        if (body.isEmpty() && message.getAttachments().isEmpty()) {
            throw AppException.badRequest("A message can't be empty");
        }
        Instant editedAt = JsValues.now();
        mongo.updateFirst(ChatAccess.byId(message.getId()), new Update().set("text", body).set("editedAt", editedAt).unset("link").set("updatedAt", editedAt), Message.class);
        message.setText(body);
        message.setEditedAt(editedAt);
        message.setLink(null);
        Conversation conversation = loaded.conversation();
        if (conversation.getLastMessage() != null && message.getId().equals(conversation.getLastMessage().getMessage())) {
            delivery.touch(conversation, message, null);
        }
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("text", body);
        patch.put("editedAt", editedAt);
        patch.put("link", null);
        delivery.broadcastUpdate(conversation, message.getId(), patch);
        if (!body.isEmpty() && message.getAttachments().isEmpty()) {
            delivery.previewLinkLater(message, access.recipientIds(conversation));
        }
        return views.serializeOne(message, actor.id());
    }

    public DeleteResult deleteMessage(AuthUser actor, String messageId, String scope) {
        LoadedMessage loaded = access.loadMessage(actor.id(), messageId);
        Message message = loaded.message();
        if ("everyone".equals(scope)) {
            boolean own = actor.id().equals(message.getSender());
            if (!own && !loaded.access().isAdmin()) {
                throw AppException.forbidden("You can only unsend your own messages");
            }
            if (message.getType() == MessageType.SYSTEM) {
                throw AppException.badRequest("This can't be unsent");
            }
            List<MessageAttachment> files = List.copyOf(message.getAttachments());
            Instant now = JsValues.now();
            mongo.updateFirst(ChatAccess.byId(message.getId()), new Update()
                    .set("deletedAt", now)
                    .set("text", "")
                    .set("attachments", List.of())
                    .unset("link")
                    .set("reactions", List.of())
                    .set("updatedAt", now), Message.class);
            message.setDeletedAt(now);
            message.setText("");
            message.setAttachments(new ArrayList<>());
            message.setLink(null);
            message.setReactions(new ArrayList<>());
            Conversation conversation = loaded.conversation();
            mongo.updateFirst(ChatAccess.byId(conversation.getId()), new Update().pull("pinned", new ObjectId(message.getId())).set("updatedAt", now), Conversation.class);
            if (conversation.getLastMessage() != null && message.getId().equals(conversation.getLastMessage().getMessage())) {
                delivery.touch(conversation, message, null);
            }
            Map<String, Object> patch = new LinkedHashMap<>();
            patch.put("deleted", true);
            patch.put("text", "");
            patch.put("attachments", List.of());
            patch.put("link", null);
            patch.put("reactions", List.of());
            delivery.broadcastUpdate(conversation, message.getId(), patch);
            delivery.deleteFilesLater(message.getId(), files);
            return new DeleteResult("everyone");
        }
        mongo.updateFirst(ChatAccess.byId(message.getId()), new Update().addToSet("hiddenFor", new ObjectId(actor.id())).set("updatedAt", JsValues.now()), Message.class);
        return new DeleteResult("me");
    }

    /** Sets (or with no emoji removes) the actor's reaction; each person has at most one per message. */
    public MessageView react(AuthUser actor, String messageId, String emoji) {
        LoadedMessage loaded = access.loadMessage(actor.id(), messageId);
        Message message = loaded.message();
        if (message.getDeletedAt() != null || message.getType() == MessageType.SYSTEM) {
            throw AppException.badRequest("You can't react to this message");
        }
        String value = JsValues.truthy(emoji) ? JsValues.trim(emoji) : null;
        if (value != null && !value.isEmpty() && !EMOJI.matcher(value).matches()) {
            throw AppException.badRequest("Choose an emoji");
        }
        Query byId = ChatAccess.byId(message.getId());
        Update withoutMine = new Update().pull("reactions", new Document("user", new ObjectId(actor.id()))).set("updatedAt", JsValues.now());
        Message saved;
        if (value != null && !value.isEmpty()) {
            mongo.updateFirst(byId, withoutMine, Message.class);
            saved = mongo.findAndModify(byId, new Update().push("reactions", new MessageReaction(actor.id(), value)).set("updatedAt", JsValues.now()),
                    FindAndModifyOptions.options().returnNew(true), Message.class);
        } else {
            saved = mongo.findAndModify(byId, withoutMine, FindAndModifyOptions.options().returnNew(true), Message.class);
        }
        if (saved == null) {
            throw ChatAccess.messageNotFound();
        }
        MessageView everyone = views.serializeOne(saved, "");
        delivery.broadcastUpdate(loaded.conversation(), saved.getId(), Map.of("reactions", everyone.publicReactions()));
        return views.serializeOne(saved, actor.id());
    }

    public ForwardResult forward(AuthUser actor, String messageId, List<String> conversationIds) {
        Message message = access.loadMessage(actor.id(), messageId).message();
        // Election cards stay in their club: only its members can vote.
        if (message.getDeletedAt() != null || message.getType() == MessageType.SYSTEM || message.getType() == MessageType.POLL) {
            throw AppException.badRequest("This message can't be forwarded");
        }
        List<String> targets = new LinkedHashSet<>(conversationIds == null ? List.<String>of() : conversationIds).stream().limit(5).toList();
        if (targets.isEmpty()) {
            throw AppException.badRequest("Choose where to forward it");
        }
        List<String> sent = new ArrayList<>();
        for (String target : targets) {
            Loaded loaded = access.loadAccessible(actor.id(), target);
            Conversation conversation = loaded.conversation();
            String blocked = access.sendBlockedReason(actor.id(), conversation, loaded.access());
            if (blocked != null) {
                throw AppException.forbidden(blocked);
            }
            rateLimit.check(actor.id());
            Message copy = new Message();
            copy.setConversation(conversation.getId());
            copy.setSender(actor.id());
            copy.setType(message.getType());
            copy.setText(message.getText());
            copy.setAttachments(new ArrayList<>(message.getAttachments()));
            copy.setLink(message.getLink());
            copy.setForwarded(true);
            delivery.insert(copy);
            delivery.touch(conversation, copy, null);
            delivery.announce(conversation, loaded.access(), copy, actor, access.recipientIds(conversation));
            sent.add(conversation.getId());
        }
        return new ForwardResult(sent);
    }

    /** Pins or unpins a message; like WhatsApp, three pins at most (a new pin replaces the oldest). */
    public ConversationDetail setPinned(AuthUser actor, String conversationId, String messageId, boolean pinned) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Conversation conversation = loaded.conversation();
        if (!(conversation.getType() == ConversationType.DIRECT || loaded.access().isAdmin())) {
            throw AppException.forbidden("Only admins can pin messages here");
        }
        Message message = null;
        if (ObjectId.isValid(String.valueOf(messageId))) {
            message = mongo.findOne(Query.query(Criteria.where("_id").is(new ObjectId(messageId))
                    .and("conversation").is(new ObjectId(conversation.getId())).and("deletedAt").is(null)), Message.class);
        }
        if (message == null || message.getType() == MessageType.SYSTEM) {
            throw ChatAccess.messageNotFound();
        }
        ObjectId pinId = new ObjectId(message.getId());
        mongo.updateFirst(ChatAccess.byId(conversation.getId()), new Update().pull("pinned", pinId).set("updatedAt", JsValues.now()), Conversation.class);
        if (pinned) {
            mongo.updateFirst(ChatAccess.byId(conversation.getId()), new Update().push("pinned").slice(-MAX_PINS).each(pinId), Conversation.class);
            delivery.system(conversation, actor.id(), actor.name(), actor.name() + " pinned a message", "PINNED");
        }
        gateway.emitToUsers(access.recipientIds(conversation), "conversation:updated", Map.of("conversationId", conversation.getId()));
        return conversations.getConversation(actor, conversation.getId());
    }
}
