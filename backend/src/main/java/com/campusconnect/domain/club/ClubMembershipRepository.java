package com.campusconnect.domain.club;

import org.springframework.data.mongodb.repository.MongoRepository;

public interface ClubMembershipRepository extends MongoRepository<ClubMembership, String> {
}
