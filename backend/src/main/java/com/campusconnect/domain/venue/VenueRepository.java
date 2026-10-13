package com.campusconnect.domain.venue;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface VenueRepository extends MongoRepository<Venue, String> {

    boolean existsByName(String name);
}
