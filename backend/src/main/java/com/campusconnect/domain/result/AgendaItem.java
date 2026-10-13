package com.campusconnect.domain.result;

import com.campusconnect.domain.common.SubDocument;
import java.time.Instant;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One slot of a hackathon's agenda (Hackathon.agenda). */
@Getter
@Setter
@NoArgsConstructor
public class AgendaItem extends SubDocument {

    private String title;
    private Instant startsAt;
    private String note = "";
}
