package com.campusconnect.domain.academic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.domain.MongoDocumentTest;
import java.util.List;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;

class AcademicDocumentsTest extends MongoDocumentTest {

    @Autowired
    DepartmentRepository departments;

    @Autowired
    AcademicBatchRepository batches;

    private static Department department(String code, boolean active) {
        Department department = new Department();
        department.setCode(code);
        department.setName(code + " department");
        department.setActive(active);
        return department;
    }

    private static AcademicBatch batch(String code, boolean active) {
        AcademicBatch batch = new AcademicBatch();
        batch.setCode(code);
        batch.setLabel("20" + code + " intake");
        batch.setActive(active);
        return batch;
    }

    @Test
    void departmentRoundTripAndActiveLookups() {
        Department ce = departments.save(department("CE", true));
        departments.save(department("IT", false));

        assertSameDocument(departments.findById(ce.getId()).orElseThrow(), ce);
        Document raw = raw("departments");
        assertObjectIds(raw, "_id");
        assertThat(raw.getBoolean("isActive")).isTrue();
        assertAbsent(raw, "active");
        JsonNode json = toJson(ce);
        assertThat(json.get("_id").stringValue()).isEqualTo(ce.getId());
        assertThat(json.get("isActive").booleanValue()).isTrue();
        assertThat(json.has("active")).isFalse();

        assertThat(departments.findByCode("IT")).isPresent();
        assertThat(departments.findByCodeAndIsActiveTrue("CE")).isPresent();
        assertThat(departments.findByCodeAndIsActiveTrue("IT")).isEmpty();
        assertThat(departments.findByCodeInAndIsActiveTrue(List.of("CE", "IT")))
                .extracting(Department::getCode)
                .containsExactly("CE");
        assertThatThrownBy(() -> departments.save(department("CE", false)))
                .isInstanceOf(DuplicateKeyException.class);
    }

    @Test
    void batchRoundTripAndActiveLookups() {
        AcademicBatch batch = batches.save(batch("24", true));
        batches.save(batch("19", false));

        assertSameDocument(batches.findById(batch.getId()).orElseThrow(), batch);
        assertThat(raw("academicbatches").getBoolean("isActive")).isTrue();
        assertThat(toJson(batch).get("isActive").booleanValue()).isTrue();
        assertThat(batches.findByCode("19")).isPresent();
        assertThat(batches.findByCodeAndIsActiveTrue("24")).isPresent();
        assertThat(batches.findByCodeAndIsActiveTrue("19")).isEmpty();
        assertThatThrownBy(() -> batches.save(batch("24", true))).isInstanceOf(DuplicateKeyException.class);
    }
}
