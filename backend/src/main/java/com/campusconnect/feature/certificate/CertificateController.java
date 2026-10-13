package com.campusconnect.feature.certificate;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.feature.certificate.CertificateDtos.CertificateFile;
import com.campusconnect.feature.certificate.CertificateDtos.CertificateView;
import com.campusconnect.feature.certificate.CertificateDtos.EventCertificates;
import com.campusconnect.feature.result.PathIds;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * Certificates (routes/CertificateRoutes.js under {@code /api/certificates}, and {@code GET /api/events/:id/certificates}
 * of routes/EventRoutes.js). Verification is public: an employer or a scholarship office can check a code without an
 * account; the PDF is for its owner only.
 */
@RestController
public class CertificateController {

    private static final Pattern CODE = Pattern.compile("^CERT-[A-Z0-9]{10}$", Pattern.CASE_INSENSITIVE);

    private final CertificateService certificates;

    public CertificateController(CertificateService certificates) {
        this.certificates = certificates;
    }

    private static String requireCode(String code) {
        if (code == null || !CODE.matcher(code).matches()) {
            throw AppException.badRequest("Invalid certificate ID");
        }
        return code;
    }

    @PublicApi
    @GetMapping("/api/certificates/verify/{code}")
    public ApiResponse<Object> verify(@PathVariable String code) {
        return ApiResponse.ok("Certificate checked", certificates.verify(requireCode(code)));
    }

    @GetMapping("/api/certificates/mine")
    public ApiResponse<List<CertificateView>> mine(@AuthenticationPrincipal AuthUser actor) {
        return ApiResponse.ok("Certificates fetched", certificates.listMine(actor));
    }

    @GetMapping("/api/certificates/{code}/pdf")
    public ResponseEntity<byte[]> download(@AuthenticationPrincipal AuthUser actor, @PathVariable String code) {
        CertificateFile file = certificates.certificateFile(actor, requireCode(code));
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .body(file.pdf());
    }

    @GetMapping("/api/events/{id}/certificates")
    public ApiResponse<EventCertificates> forEvent(@AuthenticationPrincipal AuthUser actor, @PathVariable String id) {
        return ApiResponse.ok("Certificates fetched", certificates.myCertificatesForEvent(actor, PathIds.require("id", id)));
    }
}
