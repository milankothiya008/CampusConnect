package com.campusconnect.domain.social;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class SocialDocumentsTest extends MongoDocumentTest {

    @Autowired
    FeedPostRepository posts;

    @Autowired
    StoryRepository stories;

    @Autowired
    StoryViewRepository views;

    @Autowired
    LikeRepository likes;

    // ------------------------------------------------------------------ FeedPost

    private static FeedPost post(FeedPostType type) {
        FeedPost post = new FeedPost();
        post.setType(type);
        post.setClub(newId());
        post.setAuthor(newId());
        post.setTitle("Auditions this Friday");
        return post;
    }

    @Test
    void audiencePostRoundTripKeepsRecipientsOutOfTheJson() {
        FeedPost post = post(FeedPostType.ANNOUNCEMENT);
        post.setEvent(newId());
        post.setResult(newId());
        post.setBody("Bring your instrument.");
        post.setImage("https://res.cloudinary.com/demo/image/upload/v1/a.jpg");
        post.setVisibility(FeedVisibility.AUDIENCE);
        post.setSystem(true);
        FeedAudience audience = new FeedAudience();
        audience.setMode(AnnouncementAudience.CUSTOM);
        audience.setRoles(List.of("PRESIDENT", "TREASURER"));
        audience.setDepartments(List.of("CE"));
        audience.setBatches(List.of("24"));
        audience.setPeople(2);
        audience.setIncludeMentor(true);
        audience.setLabel("2 roles, CE, batch 24 and 2 people");
        post.setAudience(audience);
        post.setRecipients(List.of(newId(), newId()));
        post.setRecipientCount(2);
        posts.save(post);

        assertSameDocument(posts.findById(post.getId()).orElseThrow(), post);
        Document raw = raw("feedposts");
        assertObjectIds(raw, "_id", "club", "event", "result", "author", "recipients.0", "recipients.1");
        // The MongoDB field keeps the Mongoose name.
        assertThat(raw.getBoolean("isSystem")).isTrue();
        assertAbsent(raw, "system", "audience._id");
        assertThat(valueAt(raw, "audience.mode")).isEqualTo("CUSTOM");

        JsonNode json = toJson(post);
        assertThat(json.get("_id").stringValue()).isEqualTo(post.getId());
        assertThat(json.get("isSystem").booleanValue()).isTrue();
        assertThat(json.has("system")).isFalse();
        assertThat(json.has("recipients")).isFalse(); // select: false
        assertThat(json.get("recipientCount").intValue()).isEqualTo(2);
        assertThat(json.get("audience").get("includeMentor").booleanValue()).isTrue();
        assertThat(json.get("visibility").stringValue()).isEqualTo("AUDIENCE");
    }

    @Test
    void ordinaryPostStoresNoRecipients() {
        FeedPost post = posts.save(post(FeedPostType.EVENT_UPDATE));

        Document raw = raw("feedposts");
        assertAbsent(raw, "recipients", "audience", "recipientCount", "event");
        assertThat(raw.getBoolean("isSystem")).isFalse();
        assertThat(raw.getString("visibility")).isEqualTo("PUBLIC");
        JsonNode json = toJson(post);
        assertThat(json.get("audience").isNull()).isTrue();
        assertThat(json.get("isSystem").booleanValue()).isFalse();
        assertThat(json.get("body").stringValue()).isEmpty();
    }

    // ------------------------------------------------------------------ Story, StoryView

    @Test
    void storyRoundTrip() {
        Story story = new Story();
        story.setClub(newId());
        story.setAuthor(newId());
        MediaFile media = new MediaFile();
        media.setKind(MediaKind.IMAGE);
        media.setProvider("event");
        media.setKey("https://res.cloudinary.com/demo/image/upload/v1/poster.jpg");
        story.setMedia(media);
        story.setCaption("Tonight!");
        story.setEvent(newId());
        story.setViewCount(10);
        story.setLikeCount(4);
        story.setExpiresAt(at(1440));
        story.setCleanupAttempts(1);
        stories.save(story);

        assertSameDocument(stories.findById(story.getId()).orElseThrow(), story);
        Document raw = raw("stories");
        assertObjectIds(raw, "_id", "club", "author", "event");
        assertDates(raw, "expiresAt", "createdAt");
        assertAbsent(raw, "media.version", "media._id");
        JsonNode json = toJson(story);
        assertThat(json.get("media").get("provider").stringValue()).isEqualTo("event");
        assertThat(json.get("media").get("kind").stringValue()).isEqualTo("IMAGE");
        assertThat(json.get("media").get("width").isNull()).isTrue();
    }

    private static StoryView view(String story, String user) {
        StoryView view = new StoryView();
        view.setStory(story);
        view.setUser(user);
        return view;
    }

    @Test
    void storyViewHasNoTimestampsAndOneRowPerViewer() {
        StoryView view = view(newId(), newId());
        view.setViewedAt(at(5));
        view.setLiked(true);
        view.setLikedAt(at(6));
        views.save(view);

        assertSameDocument(views.findByStoryAndUser(view.getStory(), view.getUser()).orElseThrow(), view);
        Document raw = raw("storyviews");
        assertObjectIds(raw, "_id", "story", "user");
        assertAbsent(raw, "createdAt", "updatedAt", "__v");
        assertThat(raw.getBoolean("liked")).isTrue();
        JsonNode json = toJson(view);
        assertThat(json.get("_id").stringValue()).isEqualTo(view.getId());
        assertThat(json.has("createdAt")).isFalse();
        assertThat(json.get("liked").booleanValue()).isTrue();

        assertThatThrownBy(() -> views.save(view(view.getStory(), view.getUser())))
                .isInstanceOf(DuplicateKeyException.class);
        assertThat(new StoryView().getViewedAt()).isNotNull(); // default: Date.now
    }

    // ------------------------------------------------------------------ Like

    private static Like like(LikeTargetType type, String target, String user) {
        Like like = new Like();
        like.setTargetType(type);
        like.setTarget(target);
        like.setUser(user);
        return like;
    }

    @Test
    void likeRoundTripAndOneLikePerPerson() {
        String target = newId();
        String user = newId();
        Like like = likes.save(like(LikeTargetType.MEDIA, target, user));

        assertSameDocument(likes.findById(like.getId()).orElseThrow(), like);
        assertObjectIds(raw("likes"), "_id", "target", "user");
        assertThat(raw("likes").getString("targetType")).isEqualTo("MEDIA");
        assertThat(likes.existsByTargetTypeAndTargetAndUser(LikeTargetType.MEDIA, target, user)).isTrue();
        assertThat(likes.existsByTargetTypeAndTargetAndUser(LikeTargetType.STORY, target, user)).isFalse();

        assertThatThrownBy(() -> likes.save(like(LikeTargetType.MEDIA, target, user)))
                .isInstanceOf(DuplicateKeyException.class);
        likes.save(like(LikeTargetType.EVENT, target, user)); // same id, another kind of target
    }
}
