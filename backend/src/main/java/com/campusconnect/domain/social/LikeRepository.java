package com.campusconnect.domain.social;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface LikeRepository extends MongoRepository<Like, String> {

    boolean existsByTargetTypeAndTargetAndUser(LikeTargetType targetType, String target, String user);
}
