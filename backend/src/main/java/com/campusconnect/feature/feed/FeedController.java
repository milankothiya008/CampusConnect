package com.campusconnect.feature.feed;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.feed.FeedDtos.AudiencePreview;
import com.campusconnect.feature.feed.FeedDtos.AudiencePreviewBody;
import com.campusconnect.feature.feed.FeedDtos.CreatePostBody;
import com.campusconnect.feature.feed.FeedDtos.FeedPostView;
import com.campusconnect.feature.feed.FeedDtos.FeedQuery;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/feed (routes/FeedRoutes.js): signed-in, verified users only. */
@RestController
@RequestMapping("/api/feed")
public class FeedController {

    private final FeedService feed;

    public FeedController(FeedService feed) {
        this.feed = feed;
    }

    @GetMapping
    public ApiResponse<List<FeedPostView>> list(@AuthenticationPrincipal AuthUser actor, @Valid @ModelAttribute FeedQuery query) {
        return ApiResponse.page("Feed fetched", feed.listFeed(actor, query));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FeedPostView>> create(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody CreatePostBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Post published", feed.createPost(actor, body)));
    }

    /** How many people an announcement would reach, before sending it. */
    @PostMapping("/audience-preview")
    public ApiResponse<AudiencePreview> audiencePreview(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody AudiencePreviewBody body) {
        return ApiResponse.ok("OK", feed.previewAudience(actor, body));
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        feed.deletePost(actor, ObjectIds.require(id, "id"));
        return ApiResponse.ok("Post deleted");
    }
}
