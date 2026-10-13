package com.campusconnect.domain.club;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClubCreationRequestRepository extends MongoRepository<ClubCreationRequest, String> {
}
