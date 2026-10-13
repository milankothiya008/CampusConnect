package com.campusconnect.domain.event;

import com.campusconnect.domain.common.SubDocument;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One line of what was actually spent (Event.expenses.items). */
@Getter
@Setter
@NoArgsConstructor
public class Expense extends SubDocument {

    private String item;

    /** Rupees, rounded to paise, 0–10000000. */
    private double amount;

    private String note = "";
}
