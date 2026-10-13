package com.campusconnect.domain.academic;

import com.campusconnect.domain.common.BaseDocument;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** An admission batch (models/AcademicBatch.js); the code is the 2-digit year in student e-mails. */
@Getter
@Setter
@NoArgsConstructor
@Document("academicbatches")
public class AcademicBatch extends BaseDocument {

    @Indexed(name = "code_1", unique = true)
    private String code;

    private String label;

    private boolean isActive = true;

    @JsonProperty("isActive")
    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean value) {
        this.isActive = value;
    }
}
