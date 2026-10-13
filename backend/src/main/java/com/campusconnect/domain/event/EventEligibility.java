package com.campusconnect.domain.event;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Who may register (Event.eligibility): department and batch codes (empty = everyone) and a free-text note. */
@Getter
@Setter
@NoArgsConstructor
public class EventEligibility {

    private List<String> departments = new ArrayList<>();
    private List<String> batches = new ArrayList<>();
    private String notes = "";
}
