package com.campusconnect.feature.chat;

import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthUserService;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationMember;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.chat.LastMessage;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.BulkOperations;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The two busiest live signals (ChatService's {@code typing} and {@code delivered} handlers), kept lean: typing in a
 * club chat costs no database query at all (club members and paused clubs are cached), typing elsewhere one
 * projected read; "delivered" one read and, only when something was waiting, one write. Club chats have no
 * per-person delivery ticks, so nothing is done for them.
 */
@Service
public class ChatSignals {

    private final MongoOperations mongo;
    private final ClubChatMembers clubMembers;
    private final PausedClubs pausedClubs;
    private final AuthUserService users;
    private final RealtimeGateway gateway;

    public ChatSignals(MongoOperations mongo, ClubChatMembers clubMembers, PausedClubs pausedClubs, AuthUserService users, RealtimeGateway gateway) {
        this.mongo = mongo;
        this.clubMembers = clubMembers;
        this.pausedClubs = pausedClubs;
        this.users = users;
        this.gateway = gateway;
    }

    /** "Asha is typing…" (or recording a voice note) to everyone else in the chat. */
    public void typing(String userId, String conversationId, boolean recording) {
        if (!ObjectId.isValid(String.valueOf(conversationId))) {
            return;
        }
        Query query = ChatAccess.byId(conversationId);
        query.fields().include("type").include("club").include("members.user");
        Conversation conversation = mongo.findOne(query, Conversation.class);
        if (conversation == null) {
            return;
        }
        List<String> everyone;
        if (conversation.getType() == ConversationType.CLUB) {
            // A club chat exists only for clubs that became active; a paused club's chat is closed.
            if (conversation.getClub() == null || pausedClubs.ids().contains(conversation.getClub())) {
                return;
            }
            everyone = clubMembers.ids(conversation.getClub());
        } else {
            everyone = conversation.getMembers().stream().map(ConversationMember::getUser).filter(Objects::nonNull).toList();
        }
        if (!everyone.contains(userId)) {
            return;
        }
        AuthUser user = users.find(userId).orElse(null);
        if (user == null) {
            return;
        }
        Map<String, Object> who = new LinkedHashMap<>();
        who.put("_id", user.id());
        who.put("name", user.name());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversation.getId());
        payload.put("user", who);
        payload.put("recording", recording);
        gateway.emitToUsers(everyone.stream().filter(id -> !id.equals(userId)).toList(), "typing", payload);
    }

    /** The user's device received the chat's latest messages (grey double tick). */
    public void delivered(String userId, String conversationId) {
        if (!ObjectId.isValid(String.valueOf(conversationId))) {
            return;
        }
        Query query = Query.query(Criteria.where("_id").is(new ObjectId(conversationId)).and("type").ne(ConversationType.CLUB).and("members.user").is(new ObjectId(userId)));
        query.fields().include("type").include("lastMessage").include("members");
        Conversation conversation = mongo.findOne(query, Conversation.class);
        if (conversation != null) {
            markDeliveredMany(userId, List.of(conversation));
        }
    }

    /** Marks waiting messages of these chats as delivered to the user (one bulk write) and tells the senders. */
    public void markDeliveredMany(String userId, Collection<Conversation> conversations) {
        Instant now = JsValues.now();
        List<Conversation> waiting = conversations.stream().filter(conversation -> isWaiting(conversation, userId)).toList();
        if (waiting.isEmpty()) {
            return;
        }
        BulkOperations bulk = mongo.bulkOps(BulkOperations.BulkMode.UNORDERED, Conversation.class);
        for (Conversation conversation : waiting) {
            bulk.updateOne(Query.query(Criteria.where("_id").is(new ObjectId(conversation.getId())).and("members.user").is(new ObjectId(userId))),
                    new Update().set("members.$.lastDeliveredAt", now).set("updatedAt", now));
        }
        bulk.execute();
        for (Conversation conversation : waiting) {
            List<String> others = conversation.getMembers().stream().map(ConversationMember::getUser).filter(id -> id != null && !id.equals(userId)).toList();
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("conversationId", conversation.getId());
            receipt.put("userId", userId);
            receipt.put("deliveredAt", now);
            gateway.emitToUsers(others, "receipt", receipt);
        }
    }

    /** Someone else's message arrived after the user's last delivered mark (club chats have no ticks). */
    private static boolean isWaiting(Conversation conversation, String userId) {
        LastMessage last = conversation.getLastMessage();
        if (conversation.getType() == ConversationType.CLUB || last == null || last.getAt() == null || userId.equals(last.getSender())) {
            return false;
        }
        ConversationMember me = ChatAccess.memberOf(conversation, userId);
        return me != null && (me.getLastDeliveredAt() == null || me.getLastDeliveredAt().isBefore(last.getAt()));
    }
}
