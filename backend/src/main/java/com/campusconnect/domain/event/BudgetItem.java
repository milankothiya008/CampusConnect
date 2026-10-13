package com.campusconnect.domain.event;

import com.campusconnect.domain.common.SubDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A budget line the club asks for (Event.budgetItems); the faculty mentor approves it with the event. */
@Getter
@Setter
@NoArgsConstructor
public class BudgetItem extends SubDocument {

    private String item;

    /** A whole number, 1–100000. */
    private int quantity = 1;

    /** Rupees, rounded to paise, 0–10000000. */
    private double unitCost = 0;

    private String note = "";
}
