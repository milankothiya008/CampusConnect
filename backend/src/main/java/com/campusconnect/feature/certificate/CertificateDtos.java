package com.campusconnect.feature.certificate;

import com.campusconnect.domain.result.Certificate;
import com.campusconnect.domain.result.CertificateKind;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonUnwrapped;
import java.time.Instant;
import java.util.List;

/** JSON views of certificates (CertificateService.js view/verify) and the PDF download. */
public final class CertificateDtos {

    private CertificateDtos() {
    }

    public record CertificateView(
            @JsonProperty("_id") String id,
            String code,
            CertificateKind kind,
            String event,
            String eventTitle,
            String clubName,
            Instant eventStartAt,
            Instant eventEndAt,
            String awardTitle,
            String teamName,
            Instant issuedAt) {

        static CertificateView of(Certificate certificate) {
            return new CertificateView(certificate.getId(), certificate.getCode(), certificate.getKind(), certificate.getEvent(), certificate.getEventTitle(),
                    certificate.getClubName(), certificate.getEventStartAt(), certificate.getEventEndAt(), certificate.getAwardTitle(), certificate.getTeamName(),
                    certificate.getIssuedAt());
        }
    }

    /** GET /api/events/:id/certificates. */
    public record EventCertificates(boolean enabled, List<CertificateView> items) {
    }

    /** A known code: {@code { valid, revoked, recipientName, ...certificate }} (an unknown one is just {@code { valid: false }}). */
    public record Verification(boolean valid, boolean revoked, String recipientName, @JsonUnwrapped CertificateView certificate) {
    }

    public record CertificateFile(byte[] pdf, String filename) {
    }
}
