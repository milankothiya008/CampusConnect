package com.campusconnect.domain.result;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HackathonEntryRepository extends MongoRepository<HackathonEntry, String> {

    Optional<HackathonEntry> findByEventAndEntryKey(String event, String entryKey);
}
