package com.campusconnect.domain.social;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface StoryViewRepository extends MongoRepository<StoryView, String> {

    Optional<StoryView> findByStoryAndUser(String story, String user);
}
