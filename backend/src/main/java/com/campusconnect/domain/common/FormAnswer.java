package com.campusconnect.domain.common;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A person's answer to one {@link FormQuestion} (models/FormSchemas.js formAnswerSchema; no own _id). */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class FormAnswer {

    @Field(targetType = FieldType.OBJECT_ID)
    private String question;

    private String text = "";
    private List<String> choices = new ArrayList<>();
}
