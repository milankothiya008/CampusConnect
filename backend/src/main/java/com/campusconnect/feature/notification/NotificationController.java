package com.campusconnect.feature.notification;

import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthenticatedOnly;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.feature.subscription.SubscriptionService;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;

/** /api/notifications (routes/NotificationRoutes.js). */
@RestController
@RequestMapping("/api/notifications")
public class NotificationController {

    private final NotificationInbox inbox;
    private final SubscriptionService subscriptions;

    public NotificationController(NotificationInbox inbox, SubscriptionService subscriptions) {
        this.inbox = inbox;
        this.subscriptions = subscriptions;
    }

    // Unsubscribe links work without signing in, like every mailing list.
    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @GetMapping("/unsubscribe")
    public ApiResponse<Map<String, Object>> describeUnsubscribe(@RequestParam(required = false) @Size(max = 500, message = "Invalid value") String token) {
        return ApiResponse.ok("Unsubscribe link checked", inbox.describeUnsubscribe(requireToken(token)));
    }

    public record UnsubscribeBody(@Size(max = 500, message = "Invalid value") String token) {
    }

    /** Our unsubscribe page posts JSON. */
    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping(path = "/unsubscribe", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ApiResponse<Map<String, Object>> unsubscribeJson(@RequestBody(required = false) @jakarta.validation.Valid UnsubscribeBody body,
            @RequestParam(required = false) @Size(max = 500, message = "Invalid value") String token) {
        String value = body != null && body.token() != null ? body.token() : token;
        return ApiResponse.ok("You have been unsubscribed", inbox.unsubscribe(requireToken(value)));
    }

    /** Mail apps' one-click POST (RFC 8058): form body, token in the query string. */
    @PublicApi
    @RateLimited(RateLimitPolicy.AUTH)
    @PostMapping("/unsubscribe")
    public ApiResponse<Map<String, Object>> unsubscribeForm(@RequestParam(required = false) @Size(max = 500, message = "Invalid value") String token) {
        return ApiResponse.ok("You have been unsubscribed", inbox.unsubscribe(requireToken(token)));
    }

    private static String requireToken(String token) {
        if (token == null) {
            throw AppException.badRequest("Unsubscribe token is missing", ErrorCode.VALIDATION_ERROR);
        }
        return token;
    }

    @AuthenticatedOnly
    @GetMapping("/preferences")
    public ApiResponse<Map<String, Object>> getPreferences(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Email preferences fetched", inbox.getEmailPreferences(actor));
    }

    public record PreferencesBody(Boolean clubUpdates, Boolean eventRecommendations, Boolean eventActivity, Boolean recruitment) {
    }

    @AuthenticatedOnly
    @PutMapping("/preferences")
    public ApiResponse<Map<String, Object>> updatePreferences(@AuthenticationPrincipal AuthUser actor, @RequestBody(required = false) PreferencesBody body) {
        Map<String, Boolean> changes = new LinkedHashMap<>();
        if (body != null) {
            changes.put("clubUpdates", body.clubUpdates());
            changes.put("eventRecommendations", body.eventRecommendations());
            changes.put("eventActivity", body.eventActivity());
            changes.put("recruitment", body.recruitment());
        }
        return ApiResponse.ok("Email preferences saved", inbox.updateEmailPreferences(actor, changes));
    }

    @AuthenticatedOnly
    @GetMapping("/subscriptions")
    public ApiResponse<List<Map<String, Object>>> mySubscriptions(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Club notifications fetched", subscriptions.listMySubscriptions(actor));
    }

    @AuthenticatedOnly
    @GetMapping
    public ApiResponse<?> list(@AuthenticationPrincipal AuthUser actor,
            @RequestParam(required = false) String page,
            @RequestParam(required = false) String limit,
            @RequestParam(required = false) String unread) {
        NotificationInbox.Listing listing = inbox.list(actor, page, limit, unread);
        return ApiResponse.ok("Notifications fetched", listing.items(), listing.meta());
    }

    @AuthenticatedOnly
    @GetMapping("/unread-count")
    public ApiResponse<Map<String, Object>> unreadCount(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Unread count fetched", inbox.unreadSummary(actor));
    }

    @AuthenticatedOnly
    @PostMapping("/read-all")
    public ApiResponse<Map<String, Object>> markAllRead(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("All notifications marked as read", inbox.markAllRead(actor));
    }

    @AuthenticatedOnly
    @PatchMapping("/{id}/read")
    public ApiResponse<?> markRead(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Notification marked as read", inbox.markRead(actor, ObjectIds.require(id, "id")));
    }
}
