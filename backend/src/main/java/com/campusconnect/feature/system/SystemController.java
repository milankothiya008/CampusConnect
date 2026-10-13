package com.campusconnect.feature.system;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.mail.DevInbox;
import com.campusconnect.core.mail.MailService;
import com.campusconnect.core.security.PublicApi;
import com.campusconnect.core.web.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** /api/health and the development inbox (server.js, routes/DevRoutes.js). */
@RestController
public class SystemController {

    private final MailService mail;
    private final DevInbox devInbox;

    public SystemController(MailService mail, DevInbox devInbox) {
        this.mail = mail;
        this.devInbox = devInbox;
    }

    /** {@code ip} is the caller's address as the API sees it: handy for checking the proxy set-up after deploying. */
    @PublicApi
    @GetMapping("/api/health")
    public ApiResponse<Map<String, Object>> health(HttpServletRequest request) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("emailDelivery", mail.deliveryMode());
        data.put("ip", request.getRemoteAddr());
        return ApiResponse.ok("CampusConnect API is running", data);
    }

    /** Development only: e-mails captured because no provider is configured. */
    @PublicApi
    @GetMapping("/api/dev/emails")
    public ApiResponse<List<DevInbox.Entry>> devEmails(@RequestParam(required = false) String to, HttpServletRequest request) {
        if (!mail.isPreview()) {
            throw AppException.notFound("Cannot find GET " + request.getRequestURI() + " on this server");
        }
        List<DevInbox.Entry> emails = devInbox.list(to);
        return ApiResponse.ok("Development inbox fetched", emails, Map.of("total", emails.size()));
    }
}
