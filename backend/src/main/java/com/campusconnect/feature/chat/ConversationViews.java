package com.campusconnect.feature.chat;

import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationMember;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.chat.LastMessage;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ChatAccess.Access;
import com.campusconnect.feature.chat.ChatUsers.UserView;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/** One row of the chat list, also the header of an open chat (ChatService conversationView). */
@Component
public class ConversationViews {

    public record LastMessageView(String preview, Instant at, boolean mine, boolean system, String senderId) {
    }

    public record ConversationView(
            @JsonProperty("_id") String id,
            ConversationType type,
            String title,
            String avatar,
            UserView other,
            String club,
            int memberCount,
            LastMessageView lastMessage,
            Instant lastMessageAt,
            int unread,
            boolean muted,
            Instant mutedUntil,
            boolean announceOnly,
            @JsonProperty("isAdmin") boolean isAdmin,
            Instant createdAt) {

        /** {@code { _id, type, title, avatar }}: what a live message carries about its chat. */
        public Map<String, Object> header() {
            Map<String, Object> header = new LinkedHashMap<>();
            header.put("_id", id);
            header.put("type", type);
            header.put("title", title);
            header.put("avatar", avatar);
            return header;
        }
    }

    private final ChatUsers users;
    private final MessageViews messages;
    private final ClubChatMembers clubMembers;

    public ConversationViews(ChatUsers users, MessageViews messages, ClubChatMembers clubMembers) {
        this.users = users;
        this.messages = messages;
        this.clubMembers = clubMembers;
    }

    /**
     * @param people the other people of one-to-one chats when already loaded (the chat list), else null
     * @param club   the club of a club chat when already loaded, else the access's club is used
     */
    public ConversationView row(Conversation conversation, String viewerId, Access access, Map<String, User> people, Club club, int unread) {
        ConversationMember member = access.member();
        String title = conversation.getName();
        String avatar = messages.groupAvatar(conversation.getAvatar());
        User other = null;
        if (conversation.getType() == ConversationType.DIRECT) {
            String otherId = ChatAccess.otherMemberId(conversation, viewerId);
            other = people != null && people.containsKey(otherId) ? people.get(otherId) : users.loadOne(otherId);
            title = other != null && JsValues.truthy(other.getName()) ? other.getName() : "CampusConnect user";
            avatar = other != null && JsValues.truthy(other.getAvatar()) ? other.getAvatar() : null;
        } else if (conversation.getType() == ConversationType.CLUB) {
            Club theClub = club != null ? club : access.club();
            title = theClub != null && JsValues.truthy(theClub.getName()) ? theClub.getName() : conversation.getName();
            avatar = theClub != null && JsValues.truthy(theClub.getLogo()) ? theClub.getLogo() : null;
        }

        LastMessage stored = conversation.getLastMessage();
        LastMessage last = stored != null && stored.getAt() != null && !stored.getAt().isBefore(access.visibleFrom()) ? stored : null;
        LastMessageView lastView = last == null ? null : new LastMessageView(last.getPreview(), last.getAt(),
                !last.isSystem() && viewerId != null && viewerId.equals(last.getSender()), last.isSystem(), last.getSender() == null ? "" : last.getSender());
        Instant mutedUntil = member.getMutedUntil();
        return new ConversationView(
                conversation.getId(),
                conversation.getType(),
                title,
                avatar,
                ChatUsers.view(other),
                conversation.getType() == ConversationType.CLUB ? conversation.getClub() : null,
                conversation.getType() == ConversationType.CLUB ? clubMembers.ids(conversation.getClub()).size() : conversation.getMembers().size(),
                lastView,
                last == null ? null : last.getAt(),
                unread,
                mutedUntil != null && mutedUntil.isAfter(Instant.now()),
                mutedUntil,
                conversation.isAnnounceOnly(),
                access.isAdmin(),
                conversation.getCreatedAt());
    }
}
