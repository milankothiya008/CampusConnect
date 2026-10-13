package com.campusconnect.feature.feed;

import com.campusconnect.core.web.Refs;
import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.feature.feed.FeedDtos.FeedPostView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/**
 * Builds feed post views like FeedService.populatePost: one query per referenced type for a whole page of posts
 * (clubs, authors, events, venues, results, award winners), never one per post.
 */
@Component
public class FeedViews {

    static final String[] CLUB_FIELDS = {"name", "logo", "category", "status"};
    static final String[] EVENT_FIELDS = {"title", "shortDescription", "poster", "startAt", "endAt", "status", "venue", "registeredCount", "maxParticipants", "category"};
    static final String[] RESULT_FIELDS = {"summary", "awards", "status", "publishedAt"};

    private final Refs refs;

    public FeedViews(Refs refs) {
        this.refs = refs;
    }

    public List<FeedPostView> of(List<FeedPost> posts) {
        Map<String, Map<String, Object>> clubs = refs.clubs(ids(posts, FeedPost::getClub), CLUB_FIELDS);
        Map<String, Map<String, Object>> authors = refs.users(ids(posts, FeedPost::getAuthor), "name");
        Map<String, Map<String, Object>> events = events(ids(posts, FeedPost::getEvent));
        Map<String, Map<String, Object>> results = results(ids(posts, FeedPost::getResult));
        return posts.stream().map(post -> new FeedPostView(
                post.getId(),
                post.getType(),
                lookup(clubs, post.getClub()),
                lookup(events, post.getEvent()),
                lookup(results, post.getResult()),
                lookup(authors, post.getAuthor()),
                post.getTitle(),
                post.getBody(),
                post.getImage(),
                post.getVisibility(),
                post.isSystem(),
                post.getAudience(),
                post.getRecipientCount(),
                post.getCreatedAt(),
                post.getUpdatedAt())).toList();
    }

    /** Events with their venue ({@code name location}) in place of the venue id. */
    private Map<String, Map<String, Object>> events(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> events = refs.events(ids, EVENT_FIELDS);
        List<String> venueIds = events.values().stream().map(event -> (String) event.get("venue")).filter(Objects::nonNull).toList();
        Map<String, Map<String, Object>> venues = refs.load("venues", venueIds, "name", "location");
        events.values().forEach(event -> event.put("venue", lookup(venues, (String) event.get("venue"))));
        return events;
    }

    /** Results with each award's {@code recipientUser} populated with the winner's name. */
    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> results(List<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, Object>> results = refs.load("eventresults", ids, RESULT_FIELDS);
        List<Map<String, Object>> awards = new ArrayList<>();
        results.values().forEach(result -> {
            if (result.get("awards") instanceof List<?> list) {
                list.stream().filter(Map.class::isInstance).forEach(award -> awards.add((Map<String, Object>) award));
            }
        });
        Map<String, Map<String, Object>> winners = refs.users(awards.stream().map(award -> (String) award.get("recipientUser")).filter(Objects::nonNull).toList(), "name");
        awards.forEach(award -> award.put("recipientUser", lookup(winners, (String) award.get("recipientUser"))));
        return results;
    }

    private static List<String> ids(Collection<FeedPost> posts, Function<FeedPost, String> field) {
        return posts.stream().map(field).filter(Objects::nonNull).distinct().toList();
    }

    private static Map<String, Object> lookup(Map<String, Map<String, Object>> loaded, String id) {
        return id == null ? null : loaded.get(id);
    }
}
