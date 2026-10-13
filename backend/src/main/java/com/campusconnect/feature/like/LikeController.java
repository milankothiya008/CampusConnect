package com.campusconnect.feature.like;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.event.EventFeedCache;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Likes on event posts and gallery photos/videos: /api/likes/:type/:id (routes/LikeRoutes.js). */
@RestController
@RequestMapping("/api/likes")
public class LikeController {

    static final List<String> TYPES = List.of("event", "media", "EVENT", "MEDIA");

    private final LikeService likes;
    private final EventFeedCache eventFeed;

    public LikeController(LikeService likes, EventFeedCache eventFeed) {
        this.likes = likes;
        this.eventFeed = eventFeed;
    }

    /** param("type").isIn([...]).withMessage("Unknown item"), param("id").isMongoId(), then the body rules. */
    private static List<String> targetProblems(String type, String id) {
        List<String> problems = new ArrayList<>();
        if (!TYPES.contains(type)) {
            problems.add("Unknown item");
        }
        if (!ObjectIds.isValid(id)) {
            problems.add("Invalid value");
        }
        return problems;
    }

    private static void throwIfAny(List<String> problems) {
        if (!problems.isEmpty()) {
            throw AppException.badRequest(String.join(". ", problems.stream().distinct().toList()), ErrorCode.VALIDATION_ERROR);
        }
    }

    /** Like or unlike: { liked: true | false }. */
    @PutMapping("/{type}/{id}")
    public ApiResponse<Map<String, Object>> setLiked(@AuthenticationPrincipal AuthUser actor, @PathVariable String type, @PathVariable String id,
            @RequestBody(required = false) Map<String, Object> body) {
        List<String> problems = targetProblems(type, id);
        Object liked = body == null ? null : body.get("liked");
        boolean isBoolean = liked instanceof Boolean || (liked instanceof String text && List.of("true", "false", "1", "0").contains(text))
                || (liked instanceof Number number && (number.doubleValue() == 0 || number.doubleValue() == 1));
        if (!isBoolean) {
            problems.add("Invalid value");
        }
        throwIfAny(problems);
        boolean value = liked instanceof Boolean flag ? flag : List.of("true", "1").contains(String.valueOf(liked));
        Map<String, Object> state = likes.setLiked(actor, type, id, value);
        // An event's like count is part of the cached events feed (Node cleared it on every event write).
        if ("event".equalsIgnoreCase(type)) {
            eventFeed.invalidate();
        }
        return ApiResponse.ok("Saved", state);
    }

    /** Who liked it (for the people the post belongs to). */
    @GetMapping("/{type}/{id}")
    public ApiResponse<Map<String, Object>> likers(@AuthenticationPrincipal AuthUser actor, @PathVariable String type, @PathVariable String id) {
        throwIfAny(targetProblems(type, id));
        return ApiResponse.ok("Likes", likes.listLikers(actor, type, id));
    }
}
