package com.campusconnect.feature.push;

import com.campusconnect.core.push.PushService;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.AuthenticatedOnly;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.HashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/push (routes/PushRoutes.js). */
@RestController
@RequestMapping("/api/push")
public class PushController {

    private final PushService push;

    public PushController(PushService push) {
        this.push = push;
    }

    /** The public VAPID key browsers need to subscribe (null when push is switched off on the server). */
    @PublicApi
    @GetMapping("/public-key")
    public ApiResponse<Map<String, Object>> publicKey() {
        Map<String, Object> data = new HashMap<>();
        data.put("publicKey", push.publicKey());
        return ApiResponse.ok("Push key", data);
    }

    public record Keys(@NotNull(message = "Invalid value") @Size(max = 200, message = "Invalid value") String p256dh,
                       @NotNull(message = "Invalid value") @Size(max = 100, message = "Invalid value") String auth) {
    }

    public record SubscribeBody(@NotNull(message = "Invalid value") @Size(min = 10, max = 1000, message = "Invalid value") String endpoint,
                                @NotNull(message = "Invalid value") @Valid Keys keys) {
    }

    @AuthenticatedOnly
    @PostMapping("/subscribe")
    public ApiResponse<Map<String, Object>> subscribe(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody SubscribeBody body,
            @RequestHeader(value = "User-Agent", required = false) String userAgent) {
        return ApiResponse.ok("Push notifications on", push.subscribe(actor, body.endpoint(), new PushService.SubscriptionKeys(body.keys().p256dh(), body.keys().auth()), userAgent));
    }

    public record UnsubscribeBody(@NotNull(message = "Invalid value") @Size(max = 1000, message = "Invalid value") String endpoint) {
    }

    @AuthenticatedOnly
    @PostMapping("/unsubscribe")
    public ApiResponse<Map<String, Object>> unsubscribe(@AuthenticationPrincipal AuthUser actor, @Valid @RequestBody UnsubscribeBody body) {
        return ApiResponse.ok("Push notifications off", push.unsubscribe(actor, body.endpoint()));
    }
}
