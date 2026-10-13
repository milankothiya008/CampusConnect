package com.campusconnect.domain.club;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClubSubscriptionRepository extends MongoRepository<ClubSubscription, String> {
}
