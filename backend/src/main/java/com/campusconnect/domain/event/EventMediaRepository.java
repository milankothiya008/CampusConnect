package com.campusconnect.domain.event;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface EventMediaRepository extends MongoRepository<EventMedia, String> {
}
