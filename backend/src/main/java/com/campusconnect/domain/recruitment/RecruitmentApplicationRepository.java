package com.campusconnect.domain.recruitment;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RecruitmentApplicationRepository extends MongoRepository<RecruitmentApplication, String> {

    Optional<RecruitmentApplication> findByDriveAndApplicantAndPosition(
            String drive, String applicant, String position);
}
