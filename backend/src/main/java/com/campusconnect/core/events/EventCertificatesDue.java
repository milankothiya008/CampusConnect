package com.campusconnect.core.events;

/**
 * An event's attendance is final (it was completed, or certificates were switched on afterwards): the results
 * feature tells the attendees their participation certificates are ready (CertificateService.announceCertificates).
 */
public record EventCertificatesDue(String eventId) {
}
