package com.campusconnect.domain.notification;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface PushSubscriptionRepository extends MongoRepository<PushSubscription, String> {
}
