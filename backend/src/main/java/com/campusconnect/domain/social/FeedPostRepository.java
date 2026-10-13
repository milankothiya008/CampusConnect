package com.campusconnect.domain.social;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface FeedPostRepository extends MongoRepository<FeedPost, String> {
}
