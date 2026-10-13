package com.campusconnect.domain.academic;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface DepartmentRepository extends MongoRepository<Department, String> {

    Optional<Department> findByCode(String code);

    Optional<Department> findByCodeAndIsActiveTrue(String code);

    List<Department> findByCodeInAndIsActiveTrue(Collection<String> codes);
}
