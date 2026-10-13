package com.campusconnect.feature.chat;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.events.ClubMembershipChanged;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.chat.MessageType;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.election.ClubElection;
import com.campusconnect.feature.chat.MessageViews.MessageView;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/**
 * Each club's own group chat: every approved member is in it, the faculty mentor is not. The chat is created the
 * first time a member opens their chat list or something is posted to it, and other features post into it through
 * this service (election cards and notices) — the club-group part of ChatService.
 */
@Slf4j
@Service
public class ClubChats {

    /** The actor's active clubs and their memberships, keyed by club id (for checking club chats without queries). */
    record ClubMemberships(List<Club> clubs, Map<String, ClubMembership> memberships) {
    }

    private final MongoOperations mongo;
    private final ClubChatMembers members;
    private final ClubAccess clubAccess;
    private final MessageViews messageViews;
    private final ChatDelivery delivery;
    private final RealtimeGateway gateway;

    public ClubChats(MongoOperations mongo, ClubChatMembers members, ClubAccess clubAccess, MessageViews messageViews, ChatDelivery delivery, RealtimeGateway gateway) {
        this.mongo = mongo;
        this.members = members;
        this.clubAccess = clubAccess;
        this.messageViews = messageViews;
        this.delivery = delivery;
        this.gateway = gateway;
    }

    private static Conversation newClubChat(Club club) {
        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.CLUB);
        conversation.setClub(club.getId());
        conversation.setName(club.getName());
        return conversation;
    }

    /** Makes sure each active club the user belongs to has its group chat (three queries, whatever the number of clubs). */
    ClubMemberships ensureClubChats(String userId) {
        Query membershipQuery = Query.query(Criteria.where("user").is(new ObjectId(userId)).and("status").is(MembershipStatus.APPROVED));
        membershipQuery.fields().include("club").include("role").include("joinedAt").include("createdAt");
        List<ClubMembership> memberships = mongo.find(membershipQuery, ClubMembership.class);
        if (memberships.isEmpty()) {
            return new ClubMemberships(List.of(), Map.of());
        }
        Query clubQuery = Query.query(Criteria.where("_id").in(memberships.stream().map(row -> new ObjectId(row.getClub())).toList()).and("status").is(ClubStatus.ACTIVE));
        clubQuery.fields().include("name").include("logo").include("status").include("roles");
        List<Club> clubs = mongo.find(clubQuery, Club.class);
        if (!clubs.isEmpty()) {
            Query existingQuery = Query.query(Criteria.where("club").in(clubs.stream().map(club -> new ObjectId(club.getId())).toList()));
            existingQuery.fields().include("club");
            Set<String> existing = mongo.find(existingQuery, Conversation.class).stream().map(Conversation::getClub).collect(Collectors.toSet());
            for (Club club : clubs) {
                if (!existing.contains(club.getId())) {
                    try {
                        mongo.insert(newClubChat(club));
                    } catch (DuplicateKeyException createdMeanwhile) {
                        // Another request created it at the same moment.
                    }
                }
            }
        }
        Map<String, ClubMembership> byClub = new LinkedHashMap<>();
        memberships.forEach(row -> byClub.put(row.getClub(), row));
        return new ClubMemberships(clubs, byClub);
    }

    /** The club's group chat, created if nobody has opened it yet; null when the club doesn't exist. */
    public Conversation clubConversation(String clubId) {
        Conversation found = mongo.findOne(Query.query(Criteria.where("club").is(new ObjectId(clubId))), Conversation.class);
        if (found != null) {
            return found;
        }
        Query clubQuery = ChatAccess.byId(clubId);
        clubQuery.fields().include("name");
        Club club = mongo.findOne(clubQuery, Club.class);
        if (club == null) {
            return null;
        }
        try {
            return mongo.insert(newClubChat(club));
        } catch (DuplicateKeyException createdMeanwhile) {
            return mongo.findOne(Query.query(Criteria.where("club").is(new ObjectId(clubId))), Conversation.class);
        }
    }

    /** Posts an election card (a POLL message) in the club group, from the organiser. */
    public Message postElectionCard(String clubId, ClubElection election, AuthUser sender) {
        Conversation conversation = clubConversation(clubId);
        if (conversation == null) {
            return null;
        }
        Message message = new Message();
        message.setConversation(conversation.getId());
        message.setSender(sender.id());
        message.setType(MessageType.POLL);
        message.setText(election.getTitle());
        message.setPoll(election.getId());
        delivery.insert(message);
        delivery.touch(conversation, message, null);
        MessageView view = messageViews.serializeOne(message, sender.id());
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("_id", conversation.getId());
        header.put("type", conversation.getType());
        header.put("title", conversation.getName());
        header.put("avatar", null);
        gateway.emitToUsers(members.ids(clubId), "message:new", ChatDelivery.newMessagePayload(conversation.getId(), view, header, ChatDelivery.mutedIn(conversation)));
        return message;
    }

    /** A grey notice line in the club group from CampusConnect ("Voting has closed …"). */
    public Message postClubNotice(String clubId, String text, String action) {
        Conversation conversation = clubConversation(clubId);
        if (conversation == null) {
            return null;
        }
        return delivery.system(conversation, null, "CampusConnect", text, action);
    }

    /** Tells the club's members that something changed (e.g. {@code election:updated}; cards refresh themselves). */
    public void clubSignal(String clubId, String event, Object payload) {
        gateway.emitToUsers(members.ids(clubId), event, payload);
    }

    /**
     * Someone joined or left a club (ChatService clubMembershipChanged): the club's member list is read again on every
     * replica, and someone who is no longer a member loses the club chat straight away.
     */
    @EventListener
    public void onMembershipChanged(ClubMembershipChanged event) {
        try {
            if (event.clubId() == null || !ObjectId.isValid(event.clubId())) {
                return;
            }
            members.evict(event.clubId());
            if (event.userId() == null || !ObjectId.isValid(event.userId())) {
                return;
            }
            Query query = Query.query(Criteria.where("club").is(new ObjectId(event.clubId())));
            query.fields().include("_id");
            Conversation conversation = mongo.findOne(query, Conversation.class);
            if (conversation != null && clubAccess.membership(event.userId(), event.clubId()) == null) {
                gateway.emitToUser(event.userId(), "conversation:removed", Map.of("conversationId", conversation.getId()));
            }
        } catch (RuntimeException e) {
            log.warn("Club chat update failed for club {}: {}", event.clubId(), e.getMessage());
        }
    }
}
