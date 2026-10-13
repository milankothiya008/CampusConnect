package com.campusconnect.domain.result;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface HackathonRepository extends MongoRepository<Hackathon, String> {

    Optional<Hackathon> findByEvent(String event);
}
