package com.campusconnect.domain.chat;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ConversationRepository extends MongoRepository<Conversation, String> {

    Optional<Conversation> findByDirectKey(String directKey);

    Optional<Conversation> findByClub(String club);
}
