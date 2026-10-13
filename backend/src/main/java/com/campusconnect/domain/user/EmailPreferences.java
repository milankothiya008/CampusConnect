package com.campusconnect.domain.user;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Optional e-mail categories a user can switch off (constants/EmailCategories.js). Account e-mails ignore these. */
@Getter
@Setter
@NoArgsConstructor
public class EmailPreferences {
    private boolean clubUpdates = true;
    private boolean eventRecommendations = true;
    private boolean eventActivity = true;
    private boolean recruitment = true;

    /** Whether the user accepts e-mails of this optional category key (e.g. "clubUpdates"). */
    public boolean allows(String category) {
        return switch (category) {
            case "clubUpdates" -> clubUpdates;
            case "eventRecommendations" -> eventRecommendations;
            case "eventActivity" -> eventActivity;
            case "recruitment" -> recruitment;
            default -> true;
        };
    }
}
