package com.campusconnect.domain.event;

import com.campusconnect.domain.common.SubDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Equipment the club asks for (Event.equipment); approved with the event. */
@Getter
@Setter
@NoArgsConstructor
public class EquipmentItem extends SubDocument {

    private String name;

    /** A whole number, 1–10000. */
    private int quantity = 1;

    private String note = "";
}
