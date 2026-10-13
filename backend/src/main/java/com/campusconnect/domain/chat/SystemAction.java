package com.campusconnect.domain.chat;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What a SYSTEM message records (Message.system): "Asha added Bina", "Group renamed to …". {@code actor} and
 * {@code target} are display texts (names), not ids.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class SystemAction {

    private String action;
    private String actor;
    private String target;
}
