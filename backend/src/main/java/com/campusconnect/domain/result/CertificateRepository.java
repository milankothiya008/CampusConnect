package com.campusconnect.domain.result;

import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface CertificateRepository extends MongoRepository<Certificate, String> {

    Optional<Certificate> findByCode(String code);
}
