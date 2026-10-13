package com.campusconnect.domain.election;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClubElectionRepository extends MongoRepository<ClubElection, String> {
}
