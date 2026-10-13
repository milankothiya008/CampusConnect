package com.campusconnect.domain.academic;

import com.campusconnect.domain.common.BaseDocument;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

/** An academic department (models/Department.js), e.g. CE, IT. Codes are 2–4 upper-case letters. */
@Getter
@Setter
@NoArgsConstructor
@Document("departments")
public class Department extends BaseDocument {

    @Indexed(name = "code_1", unique = true)
    private String code;

    private String name;

    private boolean isActive = true;

    @JsonProperty("isActive")
    public boolean isActive() {
        return isActive;
    }

    public void setActive(boolean value) {
        this.isActive = value;
    }
}
