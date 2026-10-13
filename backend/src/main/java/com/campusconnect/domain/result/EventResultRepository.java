package com.campusconnect.domain.result;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventResultRepository extends MongoRepository<EventResult, String> {

    Optional<EventResult> findByEvent(String event);
}
