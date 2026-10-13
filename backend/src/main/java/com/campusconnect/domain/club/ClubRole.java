package com.campusconnect.domain.club;

import java.util.ArrayList;
import java.util.List;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One of a club's roles (Club.roles; no own _id). PRESIDENT, VICE_PRESIDENT and MEMBER are built in
 * ({@code system = true}); the president adds custom roles with chosen authorities (see core.access.ClubRoles).
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ClubRole {
    private String key;
    private String name;
    private String description = "";
    private List<String> permissions = new ArrayList<>();
    private boolean system = false;
    private int order = 50;
}
