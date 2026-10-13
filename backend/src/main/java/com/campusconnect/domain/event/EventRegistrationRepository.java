package com.campusconnect.domain.event;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventRegistrationRepository extends MongoRepository<EventRegistration, String> {

    Optional<EventRegistration> findByEventAndUser(String event, String user);

    Optional<EventRegistration> findByTicketCode(String ticketCode);
}
