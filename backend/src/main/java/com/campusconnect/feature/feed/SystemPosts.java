package com.campusconnect.feature.feed;

import com.campusconnect.domain.social.FeedPost;
import com.campusconnect.domain.social.FeedPostType;
import com.campusconnect.domain.social.FeedVisibility;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.stereotype.Service;

/**
 * Posts the system writes into the campus feed (FeedService.createSystemPost): an event was published, changed,
 * or its results are out. Failures are logged and never undo the action.
 */
@Slf4j
@Service
public class SystemPosts {

    private final MongoOperations mongo;

    public SystemPosts(MongoOperations mongo) {
        this.mongo = mongo;
    }

    public record Post(FeedPostType type, String club, String event, String result, String author, String title, String body, String image) {
    }

    public FeedPost create(Post post) {
        try {
            FeedPost feedPost = new FeedPost();
            feedPost.setType(post.type());
            feedPost.setClub(post.club());
            feedPost.setEvent(post.event());
            feedPost.setResult(post.result());
            feedPost.setAuthor(post.author());
            feedPost.setTitle(post.title());
            feedPost.setBody(post.body() == null ? "" : post.body());
            feedPost.setImage(post.image());
            feedPost.setVisibility(FeedVisibility.PUBLIC);
            feedPost.setSystem(true);
            return mongo.insert(feedPost);
        } catch (RuntimeException e) {
            log.error("Failed to create feed post ({}): {}", post.type(), e.getMessage());
            return null;
        }
    }
}
