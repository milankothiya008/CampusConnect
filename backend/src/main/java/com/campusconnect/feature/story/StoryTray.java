package com.campusconnect.feature.story;

import com.campusconnect.core.cache.CacheEvictionBus;
import com.campusconnect.core.cache.LocalCaches;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.media.MediaStore.MediaUrls;
import com.campusconnect.core.media.MediaStores;
import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.club.ClubStatus;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.event.EventStatus;
import com.campusconnect.domain.social.Story;
import com.github.benmanes.caffeine.cache.Cache;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * The live stories, grouped by club (StoryService loadTray). Every feed visit asks for the tray but the set of live
 * stories changes rarely, so each replica keeps it for up to 30 seconds — less when a story expires sooner — and
 * every replica drops it as soon as a story is posted, deleted or swept. Per-person details (seen, liked, counts for
 * managers) are never cached.
 */
@Component
public class StoryTray {

    static final String CACHE = "storyTray";
    static final Duration MAX_AGE = Duration.ofSeconds(30);
    private static final String KEY = "tray";

    /** A live story with everything viewers see except their own seen/liked flags. */
    record LiveStory(String id, String club, String author, MediaKind kind, MediaUrls urls, Integer width, Integer height, Double duration,
            String caption, Map<String, Object> event, Instant createdAt, Instant expiresAt) {
    }

    /** One active club's ring: its stories, oldest first. */
    record Group(Map<String, Object> club, List<LiveStory> stories, Instant latestAt) {
    }

    record Tray(List<Group> groups, Instant validUntil) {
    }

    private final MongoOperations mongo;
    private final Refs refs;
    private final MediaStore media;
    private final CacheEvictionBus evictionBus;
    private final Cache<String, Tray> cache;

    public StoryTray(MongoOperations mongo, Refs refs, MediaStores mediaStores, LocalCaches caches, CacheEvictionBus evictionBus) {
        this.mongo = mongo;
        this.refs = refs;
        this.media = mediaStores.story();
        this.evictionBus = evictionBus;
        this.cache = caches.cache(CACHE, MAX_AGE, 1);
    }

    /** The current tray; concurrent callers wait for one rebuild instead of each querying the database. */
    Tray current() {
        Tray tray = cache.get(KEY, key -> build());
        if (!tray.validUntil().isAfter(Instant.now())) {
            cache.asMap().remove(KEY, tray);
            tray = cache.get(KEY, key -> build());
        }
        return tray;
    }

    /** Drops the cached tray on every replica. */
    public void invalidate() {
        evictionBus.evictAll(CACHE);
    }

    private Tray build() {
        Instant now = Instant.now();
        List<Story> stories = mongo.find(Query.query(Criteria.where("expiresAt").gt(now)).with(Sort.by(Sort.Direction.ASC, "createdAt")), Story.class);
        Map<String, Map<String, Object>> clubs = refs.clubs(stories.stream().map(Story::getClub).filter(Objects::nonNull).distinct().toList(), "name", "logo", "category", "status");
        Map<String, Map<String, Object>> events = refs.events(stories.stream().map(Story::getEvent).filter(Objects::nonNull).distinct().toList(), "title", "startAt", "endAt", "status");

        Map<String, Map<String, Object>> clubOf = new LinkedHashMap<>();
        Map<String, List<LiveStory>> storiesOf = new LinkedHashMap<>();
        Instant earliestExpiry = now.plus(MAX_AGE);
        for (Story story : stories) {
            Map<String, Object> club = story.getClub() == null ? null : clubs.get(story.getClub());
            // Only active clubs' stories are shown: suspended and archived clubs disappear from the tray.
            if (club == null || !ClubStatus.ACTIVE.name().equals(club.get("status"))) {
                continue;
            }
            clubOf.computeIfAbsent(story.getClub(), id -> {
                Map<String, Object> shown = new LinkedHashMap<>();
                shown.put("_id", club.get("_id"));
                shown.put("name", club.get("name"));
                shown.put("logo", club.get("logo"));
                shown.put("category", club.get("category"));
                return shown;
            });
            storiesOf.computeIfAbsent(story.getClub(), id -> new ArrayList<>()).add(live(story, story.getEvent() == null ? null : events.get(story.getEvent())));
            if (story.getExpiresAt().isBefore(earliestExpiry)) {
                earliestExpiry = story.getExpiresAt();
            }
        }
        List<Group> groups = new ArrayList<>();
        storiesOf.forEach((club, list) -> groups.add(new Group(clubOf.get(club), List.copyOf(list), list.get(list.size() - 1).createdAt())));
        Instant soonest = now.plusSeconds(1);
        return new Tray(List.copyOf(groups), earliestExpiry.isAfter(soonest) ? earliestExpiry : soonest);
    }

    /** A story as the tray keeps it (StoryService toStory); {@code event} is the populated event or null. */
    LiveStory live(Story story, Map<String, Object> event) {
        MediaFile file = story.getMedia();
        return new LiveStory(story.getId(), story.getClub(), story.getAuthor(), file.getKind(), media.mediaUrls(file), file.getWidth(), file.getHeight(),
                file.getDuration(), story.getCaption(), publicEvent(event), story.getCreatedAt(), story.getExpiresAt());
    }

    /** The linked event, only while it is public. */
    static Map<String, Object> publicEvent(Map<String, Object> event) {
        if (event == null || EventStatus.PUBLIC.stream().noneMatch(status -> status.name().equals(event.get("status")))) {
            return null;
        }
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("_id", event.get("_id"));
        shown.put("title", event.get("title"));
        shown.put("startAt", event.get("startAt"));
        shown.put("endAt", event.get("endAt"));
        return shown;
    }
}
