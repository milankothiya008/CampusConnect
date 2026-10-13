package com.campusconnect.feature.chat;

import com.campusconnect.core.access.ClubAccess;
import com.campusconnect.core.cache.CacheEvictionBus;
import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.domain.club.ClubMembership;
import com.campusconnect.domain.club.MembershipStatus;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.bson.types.ObjectId;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Who is in each club's group chat: the club's approved members (ChatService clubMemberIds). Read on every club
 * message, typing signal and election update, so the ids are cached for 30 s per replica; a membership change
 * evicts the club on every replica ({@link ClubChats#onMembershipChanged}).
 */
@Component
public class ClubChatMembers {

    static final String CACHE = "chatClubMembers";

    private final ClubAccess clubs;
    private final MongoOperations mongo;
    private final CacheEvictionBus evictionBus;
    private final Cache<String, List<String>> cache;

    public ClubChatMembers(ClubAccess clubs, MongoOperations mongo, LocalCaches caches, CacheEvictionBus evictionBus) {
        this.clubs = clubs;
        this.mongo = mongo;
        this.evictionBus = evictionBus;
        this.cache = caches.cache(CACHE, Duration.ofSeconds(30), 1000);
    }

    /** The approved members' user ids. */
    public List<String> ids(String clubId) {
        if (clubId == null) {
            return List.of();
        }
        return cache.get(clubId, id -> List.copyOf(clubs.approvedMemberIds(id)));
    }

    /** Loads the clubs that aren't cached yet in one query (the chat list shows several club chats at once). */
    public void preload(Collection<String> clubIds) {
        List<String> missing = clubIds.stream().filter(Objects::nonNull).distinct().filter(id -> cache.getIfPresent(id) == null).toList();
        if (missing.isEmpty()) {
            return;
        }
        Query query = Query.query(Criteria.where("club").in(missing.stream().map(ObjectId::new).toList()).and("status").is(MembershipStatus.APPROVED));
        query.fields().include("club").include("user");
        Map<String, List<String>> byClub = new LinkedHashMap<>();
        missing.forEach(id -> byClub.put(id, new ArrayList<>()));
        for (ClubMembership row : mongo.find(query, ClubMembership.class)) {
            byClub.get(row.getClub()).add(row.getUser());
        }
        byClub.forEach((id, members) -> cache.put(id, List.copyOf(members)));
    }

    public void evict(String clubId) {
        if (clubId != null) {
            evictionBus.evict(CACHE, clubId);
        }
    }
}
