package com.campusconnect.feature.checkin.dto;

import jakarta.validation.constraints.Size;

/** Request bodies of the check-in routes (routes/EventRoutes.js). */
public final class CheckInBodies {

    private CheckInBodies() {
    }

    /** A scanned QR ({@code token}) or a typed ticket code; one of them is required. */
    public record Scan(
            @Size(min = 40, max = 200, message = "Invalid ticket") String token,
            @Size(min = 1, max = 20, message = "Invalid ticket code") String code) {
    }

    /** Marking a student present by hand, with an optional note ("ID checked"). */
    public record Mark(@Size(max = 200, message = "Invalid value") String note) {
    }
}
