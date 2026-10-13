package com.campusconnect.domain.event;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventFeedbackRepository extends MongoRepository<EventFeedback, String> {

    Optional<EventFeedback> findByEventAndUser(String event, String user);
}
