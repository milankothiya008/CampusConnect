package com.campusconnect.feature.ticket;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** /api/tickets (routes/TicketRoutes.js) and the student's own ticket, GET /api/events/:id/ticket. */
@RestController
public class TicketController {

    private static final Pattern TOKEN = Pattern.compile("^[A-Za-z0-9._-]{40,200}$");

    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    /**
     * The QR image embedded in ticket e-mails. Public and stateless: the token carries its own signature, and whether
     * the ticket is still valid is decided at the door, not here.
     */
    @PublicApi
    @GetMapping("/api/tickets/{token}/qr.png")
    public ResponseEntity<byte[]> qr(@PathVariable String token) {
        if (!TOKEN.matcher(token).matches()) {
            throw AppException.badRequest("Invalid ticket");
        }
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CACHE_CONTROL, "public, max-age=604800")
                .body(tickets.renderTicketPng(token));
    }

    @GetMapping("/api/events/{id}/ticket")
    public ApiResponse<Map<String, Object>> myTicket(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Ticket fetched", tickets.getMyTicket(actor, ObjectIds.require(id, "id")));
    }
}
