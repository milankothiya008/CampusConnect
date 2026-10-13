package com.campusconnect.domain.election;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ElectionBallotRepository extends MongoRepository<ElectionBallot, String> {

    boolean existsByElectionAndVoter(String election, String voter);
}
