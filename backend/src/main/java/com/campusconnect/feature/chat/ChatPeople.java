package com.campusconnect.feature.chat;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.util.Texts;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.user.GlobalRole;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ChatUsers.UserView;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * People in chat: search, blocking and the chat settings (ChatService searchPeople / setBlocked / listBlocked /
 * getSettings / updateSettings). Anyone can message any student or faculty member directly; blocking stops that.
 */
@Service
public class ChatPeople {

    public record BlockResult(boolean blocked) {
    }

    public record ChatSettings(boolean showActivityStatus, boolean chatNotifications) {
    }

    private final MongoOperations mongo;
    private final ChatUsers users;
    private final RealtimeGateway gateway;

    public ChatPeople(MongoOperations mongo, ChatUsers users, RealtimeGateway gateway) {
        this.mongo = mongo;
        this.users = users;
        this.gateway = gateway;
    }

    /** Students and faculty by name or e-mail start (never the admin or yourself); without a search, suggestions. */
    public List<UserView> searchPeople(AuthUser actor, String q) {
        String term = JsValues.trim(JsValues.text(q));
        ObjectId me = new ObjectId(actor.id());
        if (!term.isEmpty()) {
            String safe = Texts.escapeRegex(term);
            Query query = Query.query(Criteria.where("_id").ne(me).and("isActive").is(true).and("isEmailVerified").is(true)
                            .and("globalRole").in(GlobalRole.STUDENT, GlobalRole.FACULTY)
                            .orOperator(Criteria.where("name").regex(safe, "i"), Criteria.where("email").regex("^" + safe, "i")))
                    .with(Sort.by("name"))
                    .limit(25);
            query.fields().include("name").include("avatar").include("accountType").include("globalRole").include("departmentCode").include("batchCode").include("isActive");
            return mongo.find(query, User.class).stream().map(ChatUsers::view).toList();
        }
        // Suggestions: people you've chatted with, then members of your clubs.
        Query recentQuery = Query.query(Criteria.where("type").is(ConversationType.DIRECT).and("members.user").is(me).and("lastMessageAt").ne(null))
                .with(Sort.by(Sort.Direction.DESC, "lastMessageAt"))
                .limit(12);
        recentQuery.fields().include("members.user");
        List<String> recentIds = mongo.find(recentQuery, Conversation.class).stream().map(conversation -> ChatAccess.otherMemberId(conversation, actor.id())).toList();
        Query clubsQuery = Query.query(Criteria.where("user").is(me).and("status").is(MembershipStatus.APPROVED));
        clubsQuery.fields().include("club");
        List<ObjectId> myClubs = mongo.find(clubsQuery, ClubMembership.class).stream().map(row -> new ObjectId(row.getClub())).toList();
        List<String> mates = List.of();
        if (!myClubs.isEmpty()) {
            Query matesQuery = Query.query(Criteria.where("club").in(myClubs).and("status").is(MembershipStatus.APPROVED).and("user").ne(me)).limit(40);
            matesQuery.fields().include("user");
            mates = mongo.find(matesQuery, ClubMembership.class).stream().map(ClubMembership::getUser).toList();
        }
        List<String> ids = new LinkedHashSet<>(Stream.concat(recentIds.stream(), mates.stream()).filter(id -> id != null && !id.isEmpty()).toList()).stream().limit(25).toList();
        Map<String, User> people = users.load(ids);
        return ids.stream().map(people::get).filter(Objects::nonNull).filter(User::isActive).map(ChatUsers::view).toList();
    }

    public BlockResult setBlocked(AuthUser actor, String userId, boolean blocked) {
        if (actor.id().equals(userId)) {
            throw AppException.badRequest("You can't block yourself");
        }
        try {
            users.assertChatUser(userId);
        } catch (AppException notMessageable) {
            if (blocked) {
                throw AppException.notFound("Account not found");
            }
        }
        ObjectId other = new ObjectId(userId);
        Update update = blocked ? new Update().addToSet("blockedUsers", other) : new Update().pull("blockedUsers", other);
        mongo.updateFirst(ChatAccess.byId(actor.id()), update.set("updatedAt", JsValues.now()), User.class);
        String key = Stream.of(actor.id(), userId).sorted().collect(Collectors.joining("_"));
        Query query = Query.query(Criteria.where("directKey").is(key));
        query.fields().include("_id");
        Conversation conversation = mongo.findOne(query, Conversation.class);
        if (conversation != null) {
            gateway.emitToUsers(List.of(actor.id(), userId), "conversation:updated", Map.of("conversationId", conversation.getId()));
        }
        return new BlockResult(blocked);
    }

    public List<UserView> listBlocked(AuthUser actor) {
        Query query = ChatAccess.byId(actor.id());
        query.fields().include("blockedUsers");
        User me = mongo.findOne(query, User.class);
        List<String> blocked = me == null ? List.of() : me.getBlockedUsers();
        Map<String, User> people = users.load(blocked);
        return blocked.stream().map(people::get).filter(Objects::nonNull).map(ChatUsers::view).toList();
    }

    public ChatSettings getSettings(AuthUser actor) {
        Query query = ChatAccess.byId(actor.id());
        query.fields().include("showActivityStatus").include("chatNotifications");
        User me = mongo.findOne(query, User.class);
        return new ChatSettings(me == null || me.isShowActivityStatus(), me == null || me.isChatNotifications());
    }

    public ChatSettings updateSettings(AuthUser actor, ChatRequests.SettingsBody body) {
        Update update = new Update();
        if (body != null && body.showActivityStatus() != null) {
            update.set("showActivityStatus", body.showActivityStatus());
        }
        if (body != null && body.chatNotifications() != null) {
            update.set("chatNotifications", body.chatNotifications());
        }
        if (!update.getUpdateObject().isEmpty()) {
            mongo.updateFirst(ChatAccess.byId(actor.id()), update.set("updatedAt", JsValues.now()), User.class);
        }
        return getSettings(actor);
    }
}
