package com.campusconnect.domain.academic;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AcademicBatchRepository extends MongoRepository<AcademicBatch, String> {

    Optional<AcademicBatch> findByCode(String code);

    Optional<AcademicBatch> findByCodeAndIsActiveTrue(String code);
}
