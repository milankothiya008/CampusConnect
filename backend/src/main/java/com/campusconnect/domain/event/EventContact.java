package com.campusconnect.domain.event;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The event's contact person (Event.contact); the e-mail is stored lower-case. */
@Getter
@Setter
@NoArgsConstructor
public class EventContact {

    private String name = "";
    private String email = "";
    private String phone = "";
}
