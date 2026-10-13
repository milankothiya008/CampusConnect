package com.campusconnect.feature.admin;

import com.campusconnect.core.cache.CacheNames;
import com.campusconnect.core.cache.SharedCaches;
import com.campusconnect.domain.academic.AcademicBatch;
import com.campusconnect.domain.academic.Department;
import java.util.List;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Component;

/**
 * Departments and batches, read by every registration and event form (Node's referenceCache, now shared in Redis).
 * They change only when the admin edits them, and every edit clears the cache on all replicas.
 */
@Component
public class ReferenceData {

    private final MongoOperations mongo;
    private final SharedCaches sharedCaches;

    public ReferenceData(MongoOperations mongo, SharedCaches sharedCaches) {
        this.mongo = mongo;
        this.sharedCaches = sharedCaches;
    }

    /** By code; {@code activeOnly} for {@code ?active=true}. */
    @Cacheable(cacheNames = CacheNames.REFERENCE, key = "'departments:' + #p0")
    public List<Department> departments(boolean activeOnly) {
        Query query = activeOnly ? Query.query(Criteria.where("isActive").is(true)) : new Query();
        return mongo.find(query.with(Sort.by("code")), Department.class);
    }

    /** Newest batch first; {@code activeOnly} for {@code ?active=true}. */
    @Cacheable(cacheNames = CacheNames.REFERENCE, key = "'batches:' + #p0")
    public List<AcademicBatch> batches(boolean activeOnly) {
        Query query = activeOnly ? Query.query(Criteria.where("isActive").is(true)) : new Query();
        return mongo.find(query.with(Sort.by(Sort.Direction.DESC, "code")), AcademicBatch.class);
    }

    public void changed() {
        sharedCaches.clear(CacheNames.REFERENCE);
    }
}
