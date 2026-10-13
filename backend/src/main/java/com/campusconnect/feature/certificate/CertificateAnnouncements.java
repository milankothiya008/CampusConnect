package com.campusconnect.feature.certificate;

import com.campusconnect.core.events.EventCertificatesDue;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The events feature publishes {@link EventCertificatesDue} where the Node code called
 * {@code CertificateService.announceCertificates(event)} (an event was completed, or certificates were switched on
 * afterwards). The announcement is a side effect: a failure is logged and never undoes the action that published it.
 */
@Slf4j
@Component
public class CertificateAnnouncements {

    private final CertificateService certificates;

    public CertificateAnnouncements(CertificateService certificates) {
        this.certificates = certificates;
    }

    @EventListener
    public void onCertificatesDue(EventCertificatesDue due) {
        try {
            certificates.announceCertificates(due.eventId());
        } catch (RuntimeException e) {
            log.error("Failed to announce certificates for event {}: {}", due.eventId(), e.getMessage());
        }
    }
}
