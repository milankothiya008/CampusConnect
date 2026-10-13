package com.campusconnect.feature.calendar;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import java.util.Map;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Calendar files (routes/CalendarRoutes.js). The .ics addresses are opened by calendar apps, which can't sign in: one
 * event is public anyway, and the personal feed is protected by its private token.
 */
@RestController
@RequestMapping("/api/calendar")
public class CalendarController {

    static final String CALENDAR_TYPE = "text/calendar; charset=utf-8";

    private final CalendarService calendar;

    public CalendarController(CalendarService calendar) {
        this.calendar = calendar;
    }

    private static ResponseEntity<String> ics(String body, String filename) {
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, CALENDAR_TYPE)
                .cacheControl(CacheControl.noCache());
        if (filename != null) {
            response.header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename.replace("\"", "") + "\"");
        }
        return response.body(body);
    }

    @PublicApi
    @GetMapping("/events/{file}")
    public ResponseEntity<String> eventFile(@PathVariable String file) {
        CalendarService.CalendarFile download = calendar.eventCalendar(file.replaceAll("(?i)\\.ics$", ""));
        return ics(download.body(), download.filename());
    }

    @PublicApi
    @GetMapping("/feed/{token}")
    public ResponseEntity<String> feed(@PathVariable String token) {
        return ics(calendar.feedCalendar(token), null);
    }

    @GetMapping("/link")
    public ApiResponse<Map<String, Object>> link(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("OK", calendar.getFeedLink(actor));
    }

    @PostMapping("/link/reset")
    public ApiResponse<Map<String, Object>> resetLink(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Calendar link reset", calendar.resetFeedLink(actor));
    }
}
