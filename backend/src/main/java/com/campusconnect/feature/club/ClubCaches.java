package com.campusconnect.feature.club;

import com.campusconnect.core.access.PausedClubs;
import com.campusconnect.core.cache.SharedCaches;
import org.springframework.stereotype.Component;

/**
 * The Mongoose {@code clearOnWrite} hooks of the club models: a club write refreshes the paused-club ids, the events
 * feed (it shows club names) and the club directory; membership and recruitment-drive writes refresh the directory
 * (member counts and "Recruiting" badges).
 */
@Component
public class ClubCaches {

    private final PausedClubs pausedClubs;
    private final SharedCaches sharedCaches;

    public ClubCaches(PausedClubs pausedClubs, SharedCaches sharedCaches) {
        this.pausedClubs = pausedClubs;
        this.sharedCaches = sharedCaches;
    }

    /** After any club write. */
    public void clubChanged() {
        pausedClubs.evict();
        sharedCaches.clearEventFeed();
        sharedCaches.clearClubDirectory();
    }

    /** After a membership or recruitment-drive write. */
    public void directoryChanged() {
        sharedCaches.clearClubDirectory();
    }
}
