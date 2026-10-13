package com.campusconnect.feature.chat;

import com.campusconnect.core.access.ClubPermission;
import com.campusconnect.core.access.ClubRoles;
import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.realtime.PresenceRegistry;
import com.campusconnect.core.realtime.PresenceService;
import com.campusconnect.core.realtime.RealtimeGateway;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.domain.chat.Conversation;
import com.campusconnect.domain.chat.ConversationMember;
import com.campusconnect.domain.chat.ConversationMemberRole;
import com.campusconnect.domain.chat.ConversationType;
import com.campusconnect.domain.chat.Message;
import com.campusconnect.domain.chat.MessageType;
import com.campusconnect.domain.club.Club;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.user.User;
import com.campusconnect.feature.chat.ChatAccess.Access;
import com.campusconnect.feature.chat.ChatAccess.BlockState;
import com.campusconnect.feature.chat.ChatAccess.Loaded;
import com.campusconnect.feature.chat.ChatAccess.Prefetched;
import com.campusconnect.feature.chat.ChatUsers.UserView;
import com.campusconnect.feature.chat.ClubChats.ClubMemberships;
import com.campusconnect.feature.chat.ConversationViews.ConversationView;
import com.campusconnect.feature.chat.ConversationViews.LastMessageView;
import com.campusconnect.feature.chat.MessageViews.MessageView;
import com.campusconnect.domain.user.AccountType;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.text.Collator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.bson.types.ObjectId;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

/**
 * The chat list, the unread badge, opening chats and the per-person settings of a chat (read, muted, cleared):
 * ChatService listConversations / unreadSummary / openDirect / createGroup / getConversation / markRead / mute /
 * clearChat / sharedItems.
 */
@Service
public class ConversationService {

    /** Unread counts stop at 100 ("99+"), so a long-unread busy group never costs a long count. */
    static final int UNREAD_CAP = 100;
    static final int LIST_LIMIT = 200;
    /** Unread counts of one chat list run this many at a time. */
    static final int UNREAD_PARALLELISM = 8;
    /** Instagram's choices: 15 minutes, 1 hour, 8 hours, 24 hours, a week, or until you change it. */
    static final Map<String, Duration> MUTE_FOR = Map.of(
            "15m", Duration.ofMinutes(15),
            "1h", Duration.ofHours(1),
            "8h", Duration.ofHours(8),
            "24h", Duration.ofHours(24),
            "1w", Duration.ofDays(7),
            "always", Duration.ofDays(100L * 365));

    public record MemberView(
            @JsonProperty("_id") String id,
            String name,
            String avatar,
            AccountType accountType,
            String departmentCode,
            String batchCode,
            ConversationMemberRole role,
            @JsonInclude(JsonInclude.Include.NON_NULL) String clubRole) {

        static MemberView of(UserView user, ConversationMemberRole role, String clubRole) {
            return user == null
                    ? new MemberView(null, null, null, null, null, null, role, clubRole)
                    : new MemberView(user.id(), user.name(), user.avatar(), user.accountType(), user.departmentCode(), user.batchCode(), role, clubRole);
        }
    }

    /** Read state of the others, for ticks and "Seen" (one-to-one chats and groups). */
    public record Receipt(String userId, Instant readAt, Instant deliveredAt) {
    }

    /** An open chat: its list row plus members, pinned messages, receipts and what the viewer may do. */
    public record ConversationDetail(
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
            Instant createdAt,
            String description,
            List<MemberView> members,
            List<MessageView> pinned,
            List<Receipt> receipts,
            BlockState block,
            Map<String, Object> presence,
            boolean canSend,
            String sendBlockedReason,
            boolean canEditInfo,
            boolean canManageMembers,
            boolean canPin) {
    }

    /** The Messages badge: chats and messages waiting, per-chat counts so the app can keep it right live, muted chats. */
    public record UnreadSummary(int chats, int messages, Map<String, Integer> counts, int cap, List<String> mutedIds) {
    }

    public record ReadResult(Instant readAt, int unread, Instant visibleFrom) {
    }

    public record MuteResult(Instant mutedUntil, boolean muted) {
    }

    public record ClearResult(Instant clearedAt) {
    }

    record Entry(Conversation conversation, Access access, int unread) {
    }

    record Chats(List<Entry> entries, Map<String, Club> clubs) {
    }

    private final MongoOperations mongo;
    private final ChatAccess access;
    private final ChatUsers users;
    private final ConversationViews views;
    private final MessageViews messageViews;
    private final ChatDelivery delivery;
    private final ClubChats clubChats;
    private final ClubChatMembers clubMembers;
    private final ChatSignals signals;
    private final ChatBackground background;
    private final RealtimeGateway gateway;
    private final PresenceRegistry presence;
    private final AppProperties properties;

    public ConversationService(MongoOperations mongo, ChatAccess access, ChatUsers users, ConversationViews views, MessageViews messageViews, ChatDelivery delivery,
            ClubChats clubChats, ClubChatMembers clubMembers, ChatSignals signals, ChatBackground background, RealtimeGateway gateway, PresenceRegistry presence,
            AppProperties properties) {
        this.mongo = mongo;
        this.access = access;
        this.users = users;
        this.views = views;
        this.messageViews = messageViews;
        this.delivery = delivery;
        this.clubChats = clubChats;
        this.clubMembers = clubMembers;
        this.signals = signals;
        this.background = background;
        this.gateway = gateway;
        this.presence = presence;
        this.properties = properties;
    }

    // ---------------------------------------------------------------- unread counts

    /** Where the viewer's unread messages start: after their visible history began and after their last read. */
    private static Instant unreadSince(Access access) {
        Instant read = access.member().getLastReadAt();
        return read != null && read.isAfter(access.visibleFrom()) ? read : access.visibleFrom();
    }

    /** True when the chat's latest message is newer than what the viewer read, i.e. counting is worth a query. */
    private static boolean mayHaveUnread(Conversation conversation, Access access) {
        Instant last = conversation.getLastMessage() == null ? null : conversation.getLastMessage().getAt();
        return last != null && last.isAfter(unreadSince(access));
    }

    /** Unread messages from others (system lines excluded), counted up to {@link #UNREAD_CAP}. */
    int unreadIn(Conversation conversation, String actorId, Access access) {
        if (!mayHaveUnread(conversation, access)) {
            return 0;
        }
        ObjectId me = new ObjectId(actorId);
        Query query = Query.query(Criteria.where("conversation").is(new ObjectId(conversation.getId()))
                        .and("createdAt").gt(unreadSince(access))
                        .and("sender").ne(me)
                        .and("hiddenFor").ne(me)
                        .and("type").ne(MessageType.SYSTEM))
                .limit(UNREAD_CAP);
        return (int) mongo.count(query, Message.class);
    }

    /** The unread count of each chat; the ones that need a query run in parallel (like the original's Promise.all). */
    private int[] unreadCounts(List<Conversation> conversations, List<Access> accesses, String actorId) {
        int[] counts = new int[conversations.size()];
        List<Integer> pending = new ArrayList<>();
        for (int i = 0; i < conversations.size(); i++) {
            if (mayHaveUnread(conversations.get(i), accesses.get(i))) {
                pending.add(i);
            }
        }
        if (pending.size() <= 1) {
            pending.forEach(i -> counts[i] = unreadIn(conversations.get(i), actorId, accesses.get(i)));
            return counts;
        }
        Semaphore slots = new Semaphore(UNREAD_PARALLELISM);
        Map<Integer, Future<Integer>> futures = new LinkedHashMap<>();
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i : pending) {
                futures.put(i, executor.submit(() -> {
                    slots.acquire();
                    try {
                        return unreadIn(conversations.get(i), actorId, accesses.get(i));
                    } finally {
                        slots.release();
                    }
                }));
            }
        }
        for (Map.Entry<Integer, Future<Integer>> future : futures.entrySet()) {
            try {
                counts[future.getKey()] = future.getValue().get();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted while counting unread messages", e);
            } catch (ExecutionException e) {
                throw e.getCause() instanceof RuntimeException runtime ? runtime : new IllegalStateException(e.getCause());
            }
        }
        return counts;
    }

    // ---------------------------------------------------------------- listing

    private static Instant orEpoch(Instant value) {
        return value == null ? Instant.EPOCH : value;
    }

    /** Newest first by last message; chats without messages last, by when they last changed. */
    private static final Comparator<Conversation> BY_RECENT = (a, b) -> {
        if (a.getLastMessageAt() != null || b.getLastMessageAt() != null) {
            if (a.getLastMessageAt() == null) {
                return 1;
            }
            if (b.getLastMessageAt() == null) {
                return -1;
            }
            int byMessage = b.getLastMessageAt().compareTo(a.getLastMessageAt());
            if (byMessage != 0) {
                return byMessage;
            }
        }
        return orEpoch(b.getUpdatedAt()).compareTo(orEpoch(a.getUpdatedAt()));
    };

    /**
     * The actor's chats with their access, newest first (ChatService accessibleChats): their one-to-one chats and
     * groups, plus the chat of every active club they belong to — read with only their own member entry. Opening the
     * chat list also counts as "delivered" for everything waiting.
     */
    Chats accessibleChats(String actorId) {
        ClubMemberships clubs = clubChats.ensureClubChats(actorId);
        Map<String, Club> clubsById = new LinkedHashMap<>();
        clubs.clubs().forEach(club -> clubsById.put(club.getId(), club));
        Prefetched prefetched = new Prefetched(clubsById, clubs.memberships());
        ObjectId me = new ObjectId(actorId);

        Query ownQuery = Query.query(Criteria.where("type").in(ConversationType.DIRECT, ConversationType.GROUP).and("members.user").is(me))
                .with(Sort.by(Sort.Order.desc("lastMessageAt"), Sort.Order.desc("updatedAt")))
                .limit(LIST_LIMIT);
        List<Conversation> conversations = new ArrayList<>(mongo.find(ownQuery, Conversation.class));
        if (!clubsById.isEmpty()) {
            Query clubQuery = Query.query(Criteria.where("type").is(ConversationType.CLUB).and("club").in(clubsById.keySet().stream().map(ObjectId::new).toList()));
            clubQuery.fields().elemMatch("members", Criteria.where("user").is(me))
                    .include("type").include("club").include("name").include("avatar").include("lastMessage").include("lastMessageAt")
                    .include("announceOnly").include("createdAt").include("updatedAt");
            conversations.addAll(mongo.find(clubQuery, Conversation.class));
        }
        conversations.sort(BY_RECENT);
        List<Conversation> recent = conversations.size() > LIST_LIMIT ? conversations.subList(0, LIST_LIMIT) : conversations;

        List<Conversation> shown = new ArrayList<>();
        List<Access> accesses = new ArrayList<>();
        for (Conversation conversation : recent) {
            Access entryAccess = access.accessFor(actorId, conversation, prefetched);
            if (entryAccess == null) {
                continue;
            }
            // One-to-one chats show up once there's a message to show (or for whoever started them).
            boolean hasVisible = conversation.getLastMessage() != null && conversation.getLastMessage().getAt() != null
                    && !conversation.getLastMessage().getAt().isBefore(entryAccess.visibleFrom());
            if (conversation.getType() == ConversationType.DIRECT && !hasVisible) {
                continue;
            }
            shown.add(conversation);
            accesses.add(entryAccess);
        }
        int[] unread = unreadCounts(shown, accesses, actorId);
        List<Entry> entries = new ArrayList<>(shown.size());
        for (int i = 0; i < shown.size(); i++) {
            entries.add(new Entry(shown.get(i), accesses.get(i), unread[i]));
        }
        List<Conversation> delivered = List.copyOf(recent);
        background.run("Delivered receipts", () -> signals.markDeliveredMany(actorId, delivered));
        return new Chats(entries, clubsById);
    }

    public List<ConversationView> listConversations(AuthUser actor, String filter) {
        Chats chats = accessibleChats(actor.id());
        List<String> directIds = chats.entries().stream()
                .filter(entry -> entry.conversation().getType() == ConversationType.DIRECT)
                .map(entry -> ChatAccess.otherMemberId(entry.conversation(), actor.id()))
                .toList();
        Map<String, User> people = users.load(directIds);
        clubMembers.preload(chats.clubs().keySet());
        List<ConversationView> rows = new ArrayList<>(chats.entries().stream()
                .map(entry -> views.row(entry.conversation(), actor.id(), entry.access(), people, chats.clubs().get(entry.conversation().getClub()), entry.unread()))
                .toList());
        rows.sort(Comparator.comparing((ConversationView row) -> orEpoch(row.lastMessageAt() != null ? row.lastMessageAt() : row.createdAt())).reversed());
        String wanted = filter == null ? "all" : filter;
        return switch (wanted) {
            case "unread" -> rows.stream().filter(row -> row.unread() > 0).toList();
            case "groups" -> rows.stream().filter(row -> row.type() == ConversationType.GROUP || row.type() == ConversationType.CLUB).toList();
            case "clubs" -> rows.stream().filter(row -> row.type() == ConversationType.CLUB).toList();
            case "direct" -> rows.stream().filter(row -> row.type() == ConversationType.DIRECT).toList();
            default -> rows;
        };
    }

    private static boolean isMuted(ConversationMember member) {
        return member.getMutedUntil() != null && member.getMutedUntil().isAfter(Instant.now());
    }

    /** Number of chats with unread messages (the badge on the Messages icon). */
    public UnreadSummary unreadSummary(AuthUser actor) {
        List<Entry> entries = accessibleChats(actor.id()).entries();
        List<Entry> counted = entries.stream().filter(entry -> entry.unread() > 0 && !isMuted(entry.access().member())).toList();
        Map<String, Integer> counts = new LinkedHashMap<>();
        counted.forEach(entry -> counts.put(entry.conversation().getId(), entry.unread()));
        List<String> mutedIds = entries.stream().filter(entry -> isMuted(entry.access().member())).map(entry -> entry.conversation().getId()).toList();
        return new UnreadSummary(counted.size(), counted.stream().mapToInt(Entry::unread).sum(), counts, UNREAD_CAP, mutedIds);
    }

    // ---------------------------------------------------------------- starting chats

    public ConversationDetail openDirect(AuthUser actor, String userId) {
        if (actor.id().equals(userId)) {
            throw AppException.badRequest("You can't message yourself");
        }
        User other = users.assertChatUser(userId);
        String key = Stream.of(actor.id(), other.getId()).sorted().collect(Collectors.joining("_"));
        Conversation conversation = mongo.findOne(Query.query(Criteria.where("directKey").is(key)), Conversation.class);
        if (conversation == null) {
            Conversation created = new Conversation();
            created.setType(ConversationType.DIRECT);
            created.setDirectKey(key);
            created.setCreatedBy(actor.id());
            created.setMembers(new ArrayList<>(List.of(member(actor.id(), ConversationMemberRole.MEMBER, Instant.EPOCH, null), member(other.getId(), ConversationMemberRole.MEMBER, Instant.EPOCH, null))));
            try {
                conversation = mongo.insert(created);
            } catch (DuplicateKeyException startedMeanwhile) {
                conversation = mongo.findOne(Query.query(Criteria.where("directKey").is(key)), Conversation.class);
            }
        }
        return getConversation(actor, conversation.getId());
    }

    static ConversationMember member(String userId, ConversationMemberRole role, Instant joinedAt, String addedBy) {
        ConversationMember member = new ConversationMember();
        member.setUser(userId);
        member.setRole(role);
        member.setJoinedAt(joinedAt);
        member.setAddedBy(addedBy);
        return member;
    }

    public ConversationDetail createGroup(AuthUser actor, ChatRequests.GroupBody body) {
        String title = JsValues.trim(JsValues.text(body.name()));
        if (title.isEmpty()) {
            throw AppException.badRequest("Name the group");
        }
        List<String> ids = new ArrayList<>(new LinkedHashSet<>(body.members() == null ? List.of() : body.members()));
        ids.remove(actor.id());
        if (ids.isEmpty()) {
            throw AppException.badRequest("Add at least one person");
        }
        int max = properties.getChat().getMaxGroupMembers();
        if (ids.size() + 1 > max) {
            throw AppException.badRequest("Groups can have up to " + max + " people");
        }
        List<User> people = users.assertChatUsers(ids);
        Instant now = JsValues.now();
        Conversation conversation = new Conversation();
        conversation.setType(ConversationType.GROUP);
        conversation.setName(JsValues.cut(title, 80));
        conversation.setCreatedBy(actor.id());
        List<ConversationMember> members = new ArrayList<>();
        ConversationMember creator = member(actor.id(), ConversationMemberRole.ADMIN, now, null);
        creator.setLastReadAt(now);
        members.add(creator);
        people.forEach(person -> members.add(member(person.getId(), ConversationMemberRole.MEMBER, now, actor.id())));
        conversation.setMembers(members);
        mongo.insert(conversation);
        delivery.system(conversation, actor.id(), actor.name(), actor.name() + " created the group \"" + conversation.getName() + "\"", "CREATED");
        return getConversation(actor, conversation.getId());
    }

    // ---------------------------------------------------------------- one chat

    private Map<String, Object> presenceFor(String viewerId, String userId) {
        if (userId == null || userId.isEmpty()) {
            return null;
        }
        Query query = Query.query(Criteria.where("_id").in(new ObjectId(viewerId), new ObjectId(userId)));
        query.fields().include("showActivityStatus").include("lastSeenAt");
        User viewer = null;
        User user = null;
        for (User row : mongo.find(query, User.class)) {
            if (row.getId().equals(userId)) {
                user = row;
            } else {
                viewer = row;
            }
        }
        if (user == null || (viewer != null && !viewer.isShowActivityStatus())) {
            return null;
        }
        return PresenceService.payload(userId, user.isShowActivityStatus(), user.getLastSeenAt(), presence.isOnline(userId));
    }

    private List<MemberView> membersOf(Conversation conversation, Access chatAccess) {
        List<MemberView> members = new ArrayList<>();
        if (conversation.getType() == ConversationType.CLUB) {
            Query query = Query.query(Criteria.where("club").is(new ObjectId(conversation.getClub())).and("status").is(MembershipStatus.APPROVED)).limit(500);
            query.fields().include("user").include("role");
            List<ClubMembership> rows = mongo.find(query, ClubMembership.class);
            Map<String, User> people = users.load(rows.stream().map(ClubMembership::getUser).toList());
            Club club = chatAccess.club();
            for (ClubMembership row : rows) {
                User person = people.get(row.getUser());
                if (person != null) {
                    ConversationMemberRole role = ClubRoles.has(club, row.getRole(), ClubPermission.MANAGE_CHAT) ? ConversationMemberRole.ADMIN : ConversationMemberRole.MEMBER;
                    members.add(MemberView.of(ChatUsers.view(person), role, row.getRole()));
                }
            }
        } else {
            Map<String, User> people = users.load(conversation.getMembers().stream().map(ConversationMember::getUser).toList());
            conversation.getMembers().forEach(entry -> members.add(MemberView.of(ChatUsers.view(people.get(entry.getUser())), entry.getRole(), null)));
        }
        Collator collator = Collator.getInstance(Locale.ENGLISH);
        members.sort((a, b) -> a.role() == b.role()
                ? collator.compare(String.valueOf(a.name()), String.valueOf(b.name()))
                : a.role() == ConversationMemberRole.ADMIN ? -1 : 1);
        return members;
    }

    public ConversationDetail getConversation(AuthUser actor, String conversationId) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Conversation conversation = loaded.conversation();
        Access chatAccess = loaded.access();
        ConversationView view = views.row(conversation, actor.id(), chatAccess, null, null, unreadIn(conversation, actor.id(), chatAccess));

        List<Message> pinnedMessages = conversation.getPinned().isEmpty() ? List.of()
                : mongo.find(Query.query(Criteria.where("_id").in(conversation.getPinned().stream().map(ObjectId::new).toList()).and("deletedAt").is(null)), Message.class);
        List<Receipt> receipts = conversation.getType() == ConversationType.CLUB ? List.of()
                : conversation.getMembers().stream()
                        .filter(entry -> !actor.id().equals(entry.getUser()))
                        .map(entry -> new Receipt(entry.getUser(), entry.getLastReadAt(), entry.getLastDeliveredAt()))
                        .toList();
        boolean direct = conversation.getType() == ConversationType.DIRECT;
        String otherId = direct ? ChatAccess.otherMemberId(conversation, actor.id()) : null;
        BlockState block = direct ? access.blockState(actor.id(), otherId) : null;
        String blockedReason = access.sendBlockedReason(actor.id(), conversation, chatAccess, block);
        boolean group = conversation.getType() == ConversationType.GROUP;
        return new ConversationDetail(view.id(), view.type(), view.title(), view.avatar(), view.other(), view.club(), view.memberCount(), view.lastMessage(),
                view.lastMessageAt(), view.unread(), view.muted(), view.mutedUntil(), view.announceOnly(), view.isAdmin(), view.createdAt(),
                conversation.getDescription(),
                membersOf(conversation, chatAccess),
                messageViews.serialize(pinnedMessages, actor.id()),
                receipts,
                block,
                direct ? presenceFor(actor.id(), otherId) : null,
                blockedReason == null,
                blockedReason,
                group && chatAccess.isAdmin(),
                group && chatAccess.isAdmin(),
                direct || chatAccess.isAdmin());
    }

    // ---------------------------------------------------------------- read, muted, cleared

    public ReadResult markRead(AuthUser actor, String conversationId) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Conversation conversation = loaded.conversation();
        Instant now = JsValues.now();
        mongo.updateFirst(memberQuery(conversation.getId(), actor.id()),
                new Update().set("members.$.lastReadAt", now).set("members.$.lastDeliveredAt", now).set("updatedAt", now), Conversation.class);
        if (conversation.getType() != ConversationType.CLUB) {
            List<String> others = conversation.getMembers().stream().map(ConversationMember::getUser).filter(id -> id != null && !id.equals(actor.id())).toList();
            Map<String, Object> receipt = new LinkedHashMap<>();
            receipt.put("conversationId", conversation.getId());
            receipt.put("userId", actor.id());
            receipt.put("readAt", now);
            receipt.put("deliveredAt", now);
            gateway.emitToUsers(others, "receipt", receipt);
        }
        gateway.emitToUser(actor.id(), "conversation:read", Map.of("conversationId", conversation.getId()));
        return new ReadResult(now, 0, loaded.access().visibleFrom());
    }

    static Query memberQuery(String conversationId, String userId) {
        return Query.query(Criteria.where("_id").is(new ObjectId(conversationId)).and("members.user").is(new ObjectId(userId)));
    }

    public MuteResult mute(AuthUser actor, String conversationId, String duration) {
        Conversation conversation = access.loadAccessible(actor.id(), conversationId).conversation();
        Duration length = duration == null ? null : MUTE_FOR.get(duration);
        Instant until = length == null ? null : JsValues.now().plus(length);
        mongo.updateFirst(memberQuery(conversation.getId(), actor.id()), new Update().set("members.$.mutedUntil", until).set("updatedAt", JsValues.now()), Conversation.class);
        // Their other tabs and devices update the bell icon and badge.
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversation.getId());
        payload.put("mutedUntil", until);
        gateway.emitToUser(actor.id(), "conversation:muted", payload);
        return new MuteResult(until, until != null);
    }

    /** "Delete chat" / "Clear chat": hides everything so far, for the actor only. */
    public ClearResult clearChat(AuthUser actor, String conversationId) {
        Conversation conversation = access.loadAccessible(actor.id(), conversationId).conversation();
        Instant now = JsValues.now();
        mongo.updateFirst(memberQuery(conversation.getId(), actor.id()),
                new Update().set("members.$.clearedAt", now).set("members.$.lastReadAt", now).set("updatedAt", now), Conversation.class);
        return new ClearResult(now);
    }

    /** The chat's photos and videos, links or documents (newest 90). */
    public List<MessageView> sharedItems(AuthUser actor, String conversationId, String kind) {
        Loaded loaded = access.loadAccessible(actor.id(), conversationId);
        Criteria filter = Criteria.where("conversation").is(new ObjectId(loaded.conversation().getId()))
                .and("createdAt").gte(loaded.access().visibleFrom())
                .and("hiddenFor").ne(new ObjectId(actor.id()))
                .and("deletedAt").is(null);
        if ("links".equals(kind)) {
            filter = filter.and("link.url").exists(true).ne(null);
        } else if ("docs".equals(kind)) {
            filter = filter.and("attachments.kind").is(MediaKind.DOCUMENT);
        } else {
            filter = filter.and("attachments.kind").in(MediaKind.IMAGE, MediaKind.VIDEO);
        }
        List<Message> rows = mongo.find(Query.query(filter).with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(90), Message.class);
        return messageViews.serialize(rows, actor.id());
    }
}
