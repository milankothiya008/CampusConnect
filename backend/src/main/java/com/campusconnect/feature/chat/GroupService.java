package com.campusconnect.feature.chat;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationMember;
import com.campusconnect.domain.chat.ConversationMemberRole;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.common.StoredMedia;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ChatAccess.Access;
import com.campusconnect.feature.chat.ChatAccess.Loaded;
import com.campusconnect.feature.chat.ConversationService.ConversationDetail;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * Group settings and members (ChatService updateConversation / addMembers / removeMember / setAdmin / leaveGroup).
 * Every change is one atomic update of just what changed, so two admins acting at once never undo each other.
 * Club chats follow the club's membership; only their announce-only switch can be changed here.
 */
@Service
public class GroupService {

    public record LeaveResult(boolean left) {
    }

    private final MongoOperations mongo;
    private final ChatAccess access;
    private final ChatUsers users;
    private final ChatDelivery delivery;
    private final ConversationService conversations;
    private final RealtimeGateway gateway;
    private final MediaStore store;
    private final AppProperties properties;

    public GroupService(MongoOperations mongo, ChatAccess access, ChatUsers users, ChatDelivery delivery, ConversationService conversations, RealtimeGateway gateway,
            MediaStores stores, AppProperties properties) {
        this.mongo = mongo;
        this.access = access;
        this.users = users;
        this.delivery = delivery;
        this.conversations = conversations;
        this.gateway = gateway;
        this.store = stores.chat();
        this.properties = properties;
    }

    private void signalUpdated(Conversation conversation) {
        gateway.emitToUsers(access.recipientIds(conversation), "conversation:updated", Map.of("conversationId", conversation.getId()));
    }

    /** PATCH a chat: a group's name, description, photo; any group's or club chat's announce-only switch. */
    public ConversationDetail updateConversation(AuthUser actor, String conversationId, Map<String, Object> body) {
        BodyCheck.of(body)
                .optionalString("name", 80, "Invalid value")
                .optionalString("description", 500, "Invalid value")
                .optionalBoolean("announceOnly", "Invalid value")
                .validate();
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Conversation conversation = loaded.conversation();
        if (conversation.getType() == ConversationType.DIRECT) {
            throw AppException.badRequest("One-to-one chats have no settings");
        }
        if (!loaded.access().isAdmin()) {
            throw AppException.forbidden("Only admins can change this");
        }
        Update update = new Update();
        List<String[]> notes = new ArrayList<>();
        if (conversation.getType() == ConversationType.GROUP) {
            if (body.containsKey("name")) {
                String title = JsValues.clean(body.get("name"), 80);
                if (title.isEmpty()) {
                    throw AppException.badRequest("Name the group");
                }
                if (!title.equals(conversation.getName())) {
                    notes.add(new String[] {actor.name() + " renamed the group to \"" + title + "\"", "RENAMED"});
                }
                update.set("name", title);
                conversation.setName(title);
            }
            if (body.containsKey("description")) {
                String description = JsValues.clean(body.get("description"), 500);
                update.set("description", description);
                conversation.setDescription(description);
            }
            if (body.containsKey("avatar")) {
                Object avatar = body.get("avatar");
                if (avatar == null) {
                    update.unset("avatar");
                    conversation.setAvatar(null);
                } else {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> details = avatar instanceof Map<?, ?> map ? (Map<String, Object>) map : null;
                    MediaStore.VerifiedMedia file = store.verifyUpload(details, conversation.getId(), actor.id());
                    if (file.kind() != MediaKind.IMAGE) {
                        throw AppException.badRequest("Choose a photo");
                    }
                    StoredMedia photo = new StoredMedia();
                    photo.setKind(MediaKind.IMAGE);
                    photo.setProvider(file.provider());
                    photo.setKey(file.key());
                    photo.setVersion(file.version());
                    photo.setFormat(file.format());
                    update.set("avatar", photo);
                    conversation.setAvatar(photo);
                    notes.add(new String[] {actor.name() + " changed the group photo", "PHOTO"});
                }
            }
        }
        if (body.containsKey("announceOnly") && JsValues.truthy(body.get("announceOnly")) != conversation.isAnnounceOnly()) {
            boolean announceOnly = JsValues.truthy(body.get("announceOnly"));
            update.set("announceOnly", announceOnly);
            conversation.setAnnounceOnly(announceOnly);
            notes.add(new String[] {announceOnly
                    ? actor.name() + " changed settings so only admins can send messages"
                    : actor.name() + " changed settings so everyone can send messages", "ANNOUNCE"});
        }
        if (!update.getUpdateObject().isEmpty()) {
            mongo.updateFirst(ChatAccess.byId(conversation.getId()), update.set("updatedAt", JsValues.now()), Conversation.class);
        }
        for (String[] note : notes) {
            delivery.system(conversation, actor.id(), actor.name(), note[0], note[1]);
        }
        signalUpdated(conversation);
        return conversations.getConversation(actor, conversation.getId());
    }

    private static void assertGroupAdmin(Conversation conversation, Access chatAccess) {
        if (conversation.getType() != ConversationType.GROUP) {
            throw AppException.conflict("Members of club chats follow the club's membership", ErrorCode.INVALID_STATE);
        }
        if (!chatAccess.isAdmin()) {
            throw AppException.forbidden("Only group admins can do this");
        }
    }

    public ConversationDetail addMembers(AuthUser actor, String conversationId, List<String> userIds) {
        for (int attempt = 0; ; attempt++) {
            Loaded loaded = access.loadAccessible(actor.id(), conversationId);
            Conversation conversation = loaded.conversation();
            assertGroupAdmin(conversation, loaded.access());
            Set<String> present = conversation.getMembers().stream().map(ConversationMember::getUser).collect(Collectors.toSet());
            List<String> ids = new LinkedHashSet<>(userIds == null ? List.<String>of() : userIds).stream().filter(id -> !present.contains(id)).toList();
            if (ids.isEmpty()) {
                throw AppException.badRequest("Choose people to add");
            }
            int max = properties.getChat().getMaxGroupMembers();
            if (present.size() + ids.size() > max) {
                throw AppException.badRequest("Groups can have up to " + max + " people");
            }
            List<User> people = users.assertChatUsers(ids);
            Instant now = JsValues.now();
            List<ConversationMember> added = people.stream().map(person -> ConversationService.member(person.getId(), ConversationMemberRole.MEMBER, now, actor.id())).toList();
            // Only applies while none of them is in the group yet; otherwise someone added them meanwhile: look again.
            long applied = mongo.updateFirst(
                    Query.query(Criteria.where("_id").is(new ObjectId(conversation.getId())).and("members.user").nin(ids.stream().map(ObjectId::new).toList())),
                    new Update().push("members").each(added.toArray()).set("updatedAt", now),
                    Conversation.class).getModifiedCount();
            if (applied == 0 && attempt < 2) {
                continue;
            }
            if (applied == 0) {
                throw AppException.conflict("The group changed meanwhile. Try again.");
            }
            conversation.getMembers().addAll(added);
            String names = people.stream().map(User::getName).collect(Collectors.joining(", "));
            delivery.system(conversation, actor.id(), actor.name(), actor.name() + " added " + names, "ADDED");
            return conversations.getConversation(actor, conversation.getId());
        }
    }

    /**
     * Takes someone out of a group. A group always keeps an admin: when the last one leaves, the longest-standing
     * member takes over.
     */
    private void leaveOrRemove(Conversation conversation, AuthUser actor, String userId, String text, String action) {
        ConversationMember leaving = ChatAccess.memberOf(conversation, userId);
        Conversation after = mongo.findAndModify(ChatAccess.byId(conversation.getId()),
                new Update().pull("members", new Document("user", new ObjectId(userId))).set("updatedAt", JsValues.now()),
                FindAndModifyOptions.options().returnNew(true), Conversation.class);
        if (after == null) {
            throw ChatAccess.chatNotFound();
        }
        List<ConversationMember> remaining = after.getMembers();
        boolean adminLeft = leaving != null && leaving.getRole() == ConversationMemberRole.ADMIN;
        if (adminLeft && !remaining.isEmpty() && remaining.stream().noneMatch(entry -> entry.getRole() == ConversationMemberRole.ADMIN)) {
            ConversationMember oldest = remaining.stream().min(Comparator.comparing(entry -> entry.getJoinedAt() == null ? Instant.EPOCH : entry.getJoinedAt())).orElseThrow();
            mongo.updateFirst(ConversationService.memberQuery(after.getId(), oldest.getUser()), new Update().set("members.$.role", ConversationMemberRole.ADMIN), Conversation.class);
            oldest.setRole(ConversationMemberRole.ADMIN);
        }
        gateway.emitToUser(userId, "conversation:removed", Map.of("conversationId", conversation.getId()));
        if (!remaining.isEmpty()) {
            delivery.system(after, actor.id(), actor.name(), text, action);
        }
    }

    public ConversationDetail removeMember(AuthUser actor, String conversationId, String userId) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Conversation conversation = loaded.conversation();
        assertGroupAdmin(conversation, loaded.access());
        if (actor.id().equals(userId)) {
            throw AppException.badRequest("Use Leave group to leave");
        }
        if (ChatAccess.memberOf(conversation, userId) == null) {
            throw AppException.notFound("They're not in this group");
        }
        User user = users.loadOne(userId);
        String name = user != null && JsValues.truthy(user.getName()) ? user.getName() : "a member";
        leaveOrRemove(conversation, actor, userId, actor.name() + " removed " + name, "REMOVED");
        return conversations.getConversation(actor, conversation.getId());
    }

    public ConversationDetail setAdmin(AuthUser actor, String conversationId, String userId, boolean admin) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Conversation conversation = loaded.conversation();
        assertGroupAdmin(conversation, loaded.access());
        if (ChatAccess.memberOf(conversation, userId) == null) {
            throw AppException.notFound("They're not in this group");
        }
        boolean selfDemotion = !admin && actor.id().equals(userId);
        long admins = conversation.getMembers().stream().filter(entry -> entry.getRole() == ConversationMemberRole.ADMIN).count();
        if (selfDemotion && admins == 1) {
            throw AppException.badRequest("Make someone else an admin first");
        }
        Criteria criteria = Criteria.where("_id").is(new ObjectId(conversation.getId()));
        if (selfDemotion) {
            // Two admins stepping down at the same moment must not leave the group without one.
            criteria = criteria.and("members").elemMatch(Criteria.where("user").ne(new ObjectId(actor.id())).and("role").is(ConversationMemberRole.ADMIN));
        }
        long applied = mongo.updateFirst(Query.query(criteria),
                new Update().set("members.$[target].role", admin ? ConversationMemberRole.ADMIN : ConversationMemberRole.MEMBER)
                        .set("updatedAt", JsValues.now())
                        .filterArray(Criteria.where("target.user").is(new ObjectId(userId))),
                Conversation.class).getMatchedCount();
        if (applied == 0 && selfDemotion) {
            throw AppException.badRequest("Make someone else an admin first");
        }
        signalUpdated(conversation);
        return conversations.getConversation(actor, conversation.getId());
    }

    public LeaveResult leaveGroup(AuthUser actor, String conversationId) {
        Conversation conversation = access.loadAccessible(actor.id(), conversationId).conversation();
        if (conversation.getType() != ConversationType.GROUP) {
            throw AppException.conflict(conversation.getType() == ConversationType.CLUB
                    ? "You leave a club's chat by leaving the club"
                    : "You can't leave a one-to-one chat — delete it instead", ErrorCode.INVALID_STATE);
        }
        leaveOrRemove(conversation, actor, actor.id(), actor.name() + " left", "LEFT");
        return new LeaveResult(true);
    }
}
