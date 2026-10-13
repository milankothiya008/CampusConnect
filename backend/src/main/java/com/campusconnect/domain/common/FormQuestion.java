package com.campusconnect.domain.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * An organiser-made form question (event registration forms, hackathon submission forms). Mirrors
 * {@code models/FormSchemas.js}. {@code scope} is only used by event registration forms on team events
 * ({@code TEAM}: answered once by the leader, {@code MEMBER}: by every member).
 */
@Getter
@Setter
@NoArgsConstructor
public class FormQuestion extends SubDocument {

    /** One of {@code core.util.Forms#QUESTION_TYPES}. */
    private String type;
    private String label;
    private String help = "";
    private boolean required = false;
    private List<String> options = new ArrayList<>();

    @JsonInclude(JsonInclude.Include.NON_NULL)
    private String scope;
}
