package com.campusconnect.domain.chat;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ChatReportRepository extends MongoRepository<ChatReport, String> {

    boolean existsByMessageAndReporter(String message, String reporter);
}
