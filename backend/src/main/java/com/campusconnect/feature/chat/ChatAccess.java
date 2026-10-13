package com.campusconnect.feature.chat;

import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.error.AppException;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationMember;
import com.campusconnect.domain.chat.ConversationMemberRole;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.user.User;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

/**
 * Who may do what in a chat (ChatService accessFor / loadAccessible / blockState). CLUB chats are checked against the
 * club's approved memberships on every call; DIRECT and GROUP chats against the embedded member list. Anything the
 * actor may not see is answered with 404, so strangers cannot even tell a chat exists.
 */
@Component
public class ChatAccess {

    /** The actor's membership entry, whether they administer the chat, its club and where their history starts. */
    public record Access(ConversationMember member, boolean isAdmin, Club club, Instant visibleFrom) {
    }

    /** Clubs and memberships already loaded for the chat list, so checking each club chat costs no query. */
    public record Prefetched(Map<String, Club> clubs, Map<String, ClubMembership> memberships) {
    }

    public record Loaded(Conversation conversation, Access access) {
    }

    public record LoadedMessage(Message message, Conversation conversation, Access access) {
    }

    /** {@code { iBlocked, blockedMe, inactive }} of a one-to-one chat. */
    public record BlockState(boolean iBlocked, boolean blockedMe, @JsonInclude(JsonInclude.Include.NON_NULL) Boolean inactive) {
    }

    private final MongoOperations mongo;
    private final ClubChatMembers clubMembers;

    public ChatAccess(MongoOperations mongo, ClubChatMembers clubMembers) {
        this.mongo = mongo;
        this.clubMembers = clubMembers;
    }

    static AppException chatNotFound() {
        return AppException.notFound("Chat not found");
    }

    static AppException messageNotFound() {
        return AppException.notFound("Message not found");
    }

    static Query byId(String id) {
        return Query.query(Criteria.where("_id").is(new ObjectId(id)));
    }

    static ObjectId oid(String id) {
        return id == null ? null : new ObjectId(id);
    }

    public static ConversationMember memberOf(Conversation conversation, String userId) {
        if (userId == null || conversation.getMembers() == null) {
            return null;
        }
        return conversation.getMembers().stream().filter(entry -> userId.equals(entry.getUser())).findFirst().orElse(null);
    }

    /** The other person of a one-to-one chat ("" when there is none). */
    public static String otherMemberId(Conversation conversation, String actorId) {
        return conversation.getMembers().stream().map(ConversationMember::getUser).filter(user -> user != null && !user.equals(actorId)).findFirst().orElse("");
    }

    /** The club with the fields a chat needs (name, logo, status, roles), or null. */
    Club loadClub(String clubId) {
        if (clubId == null) {
            return null;
        }
        Query query = byId(clubId);
        query.fields().include("name").include("logo").include("status").include("roles");
        return mongo.findOne(query, Club.class);
    }

    /** The user's approved membership of the club (role, joinedAt, createdAt), or null. */
    ClubMembership approvedMembership(String clubId, String userId) {
        Query query = Query.query(Criteria.where("club").is(oid(clubId)).and("user").is(oid(userId)).and("status").is(MembershipStatus.APPROVED));
        query.fields().include("role").include("joinedAt").include("createdAt").include("club");
        return mongo.findOne(query, ClubMembership.class);
    }

    /** What the actor may do in this chat, or null when it isn't theirs. */
    public Access accessFor(String actorId, Conversation conversation, Prefetched prefetched) {
        ConversationMember member = memberOf(conversation, actorId);
        boolean isAdmin;
        Club club = null;
        if (conversation.getType() == ConversationType.CLUB) {
            club = prefetched != null ? prefetched.clubs().get(conversation.getClub()) : loadClub(conversation.getClub());
            if (club == null || club.getStatus() != ClubStatus.ACTIVE) {
                return null;
            }
            ClubMembership membership = prefetched != null ? prefetched.memberships().get(club.getId()) : approvedMembership(club.getId(), actorId);
            if (membership == null) {
                return null;
            }
            isAdmin = ClubRoles.has(club, membership.getRole(), ClubPermission.MANAGE_CHAT);
            if (member == null) {
                // First visit: remember them, with history from when they joined the club (or the chat began).
                member = new ConversationMember();
                member.setUser(actorId);
                member.setRole(ConversationMemberRole.MEMBER);
                member.setJoinedAt(firstNonNull(membership.getJoinedAt(), membership.getCreatedAt(), JsValues.now()));
                mongo.updateFirst(
                        Query.query(Criteria.where("_id").is(oid(conversation.getId())).and("members.user").ne(oid(actorId))),
                        new Update().push("members", member),
                        Conversation.class);
                conversation.getMembers().add(member);
            }
        } else {
            if (member == null) {
                return null;
            }
            isAdmin = conversation.getType() == ConversationType.GROUP && member.getRole() == ConversationMemberRole.ADMIN;
        }
        return new Access(member, isAdmin, club, visibleFrom(member));
    }

    static Instant visibleFrom(ConversationMember member) {
        Instant joined = member.getJoinedAt() == null ? Instant.EPOCH : member.getJoinedAt();
        Instant cleared = member.getClearedAt() == null ? Instant.EPOCH : member.getClearedAt();
        return joined.isAfter(cleared) ? joined : cleared;
    }

    @SafeVarargs
    private static <T> T firstNonNull(T... values) {
        for (T value : values) {
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    public Loaded loadAccessible(String actorId, String conversationId) {
        if (!ObjectId.isValid(String.valueOf(conversationId))) {
            throw chatNotFound();
        }
        Conversation conversation = mongo.findById(conversationId, Conversation.class);
        if (conversation == null) {
            throw chatNotFound();
        }
        Access access = accessFor(actorId, conversation, null);
        if (access == null) {
            throw chatNotFound();
        }
        return new Loaded(conversation, access);
    }

    /** A message the actor can see: in a chat of theirs, inside their visible history, not deleted for them. */
    public LoadedMessage loadMessage(String actorId, String messageId) {
        if (!ObjectId.isValid(String.valueOf(messageId))) {
            throw messageNotFound();
        }
        Message message = mongo.findById(messageId, Message.class);
        if (message == null) {
            throw messageNotFound();
        }
        Loaded loaded = loadAccessible(actorId, message.getConversation());
        if (message.getCreatedAt().isBefore(loaded.access().visibleFrom()) || message.getHiddenFor().contains(actorId)) {
            throw messageNotFound();
        }
        return new LoadedMessage(message, loaded.conversation(), loaded.access());
    }

    /** Everyone who receives the chat's live events. */
    public List<String> recipientIds(Conversation conversation) {
        if (conversation.getType() == ConversationType.CLUB) {
            return clubMembers.ids(conversation.getClub());
        }
        return conversation.getMembers().stream().map(ConversationMember::getUser).filter(Objects::nonNull).toList();
    }

    /** Blocking in a one-to-one chat: either side blocking stops new messages. One query for both people. */
    public BlockState blockState(String actorId, String otherId) {
        if (otherId == null || otherId.isEmpty()) {
            return new BlockState(false, false, null);
        }
        Query query = Query.query(Criteria.where("_id").in(oid(actorId), oid(otherId)));
        query.fields().include("blockedUsers").include("isActive");
        User me = null;
        User other = null;
        for (User user : mongo.find(query, User.class)) {
            if (user.getId().equals(actorId)) {
                me = user;
            } else {
                other = user;
            }
        }
        boolean iBlocked = me != null && me.getBlockedUsers().contains(otherId);
        boolean blockedMe = other != null && other.getBlockedUsers().contains(actorId);
        return new BlockState(iBlocked, blockedMe, other == null || !other.isActive());
    }

    /** Why the actor can't send here right now, or null. */
    public String sendBlockedReason(String actorId, Conversation conversation, Access access) {
        return sendBlockedReason(actorId, conversation, access, null);
    }

    /** {@link #sendBlockedReason(String, Conversation, Access)} reusing a block state already loaded (or null). */
    public String sendBlockedReason(String actorId, Conversation conversation, Access access, BlockState known) {
        if (conversation.isAnnounceOnly() && !access.isAdmin()) {
            return conversation.getType() == ConversationType.CLUB ? "Only club admins can send messages in this group" : "Only group admins can send messages";
        }
        if (conversation.getType() == ConversationType.DIRECT) {
            BlockState state = known != null ? known : blockState(actorId, otherMemberId(conversation, actorId));
            if (state.iBlocked()) {
                return "You blocked this account. Unblock to send messages.";
            }
            if (state.blockedMe() || Boolean.TRUE.equals(state.inactive())) {
                return "You can't message this account";
            }
        }
        return null;
    }
}
