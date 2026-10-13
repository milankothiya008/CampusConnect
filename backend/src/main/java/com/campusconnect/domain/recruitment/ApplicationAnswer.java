package com.campusconnect.domain.recruitment;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.mongodb.core.mapping.Field;
import org.springframework.data.mongodb.core.mapping.FieldType;

/** A student's answer to one {@link RecruitmentQuestion} (RecruitmentApplication.answers; no own _id). */
@Getter
@Setter
@NoArgsConstructor
public class ApplicationAnswer {

    @Field(targetType = FieldType.OBJECT_ID)
    private String question;

    private String text = "";
    private List<String> choices = new ArrayList<>();

    /** FILE questions only. */
    private AnswerFile file;
}
