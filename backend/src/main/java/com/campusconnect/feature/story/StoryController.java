package com.campusconnect.feature.story;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.story.StoryDtos.CreateStoryBody;
import com.campusconnect.feature.story.StoryDtos.LikeBody;
import com.campusconnect.feature.story.StoryDtos.Liked;
import com.campusconnect.feature.story.StoryDtos.LocalMedia;
import com.campusconnect.feature.story.StoryDtos.PageQuery;
import com.campusconnect.feature.story.StoryDtos.Seen;
import com.campusconnect.feature.story.StoryDtos.StoryCard;
import com.campusconnect.feature.story.StoryDtos.StoryViewer;
import com.campusconnect.feature.story.StoryDtos.TrayGroup;
import com.campusconnect.feature.story.StoryDtos.UploadTicketBody;
import com.campusconnect.feature.story.StoryDtos.Viewers;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpHeaders;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** /api/stories (routes/StoryRoutes.js): signed-in, verified users only. */
@RestController
@RequestMapping("/api/stories")
public class StoryController {

    private final StoryService stories;

    public StoryController(StoryService stories) {
        this.stories = stories;
    }

    /** Personal (seen/liked) data: browsers may keep it but must revalidate. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<TrayGroup>>> tray(@AuthenticationPrincipal AuthUser actor) {
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "private, no-cache").body(ApiResponse.ok("Stories fetched", stories.getTray(actor)));
    }

    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/uploads")
    public ResponseEntity<ApiResponse<MediaStore.UploadTicket>> uploadTicket(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody UploadTicketBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Upload ready", stories.createUploadTicket(actor, body.club(), body.kind())));
    }

    /** Development storage only: the browser posts the file here instead of to Cloudinary. */
    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/media")
    public ResponseEntity<ApiResponse<LocalMedia>> uploadLocalMedia(@AuthenticationPrincipal AuthUser actor, @RequestParam(required = false) String club,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        if (!ObjectIds.isValid(club)) {
            throw AppException.badRequest("Choose a club");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Media uploaded", stories.uploadLocalMedia(actor, club, file)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<StoryCard>> create(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody CreateStoryBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Story posted", stories.createStory(actor, body)));
    }

    @PostMapping("/{id}/view")
    public ApiResponse<Seen> view(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        stories.recordView(actor, ObjectIds.require(id, "id"));
        return ApiResponse.ok("Story viewed", new Seen(true));
    }

    @PostMapping("/{id}/like")
    public ApiResponse<Liked> like(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody LikeBody body) {
        Liked liked = stories.setLiked(actor, ObjectIds.require(id, "id"), body.liked());
        return ApiResponse.ok(body.liked() ? "Story liked" : "Like removed", liked);
    }

    @GetMapping("/{id}/viewers")
    public ApiResponse<List<StoryViewer>> viewers(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @ModelAttribute PageQuery query) {
        Viewers viewers = stories.listViewers(actor, ObjectIds.require(id, "id"), query.page(), query.limit());
        return ApiResponse.ok("Viewers fetched", viewers.items(), viewers.meta());
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        stories.deleteStory(actor, ObjectIds.require(id, "id"));
        return ApiResponse.ok("Story deleted");
    }
}
