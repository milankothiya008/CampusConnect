package com.campusconnect.domain.social;

import java.util.ArrayList;
import java.util.List;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Who an announcement was sent to (FeedPost.audience; see AudienceService). Chosen people are kept only as a count;
 * the recipients themselves are in {@code FeedPost.recipients}.
 */
@Getter
@Setter
@NoArgsConstructor
public class FeedAudience {

    private AnnouncementAudience mode = AnnouncementAudience.EVERYONE;

    /** Club role keys. */
    private List<String> roles = new ArrayList<>();

    private List<String> departments = new ArrayList<>();
    private List<String> batches = new ArrayList<>();

    /** How many individually chosen people. */
    private int people = 0;

    private boolean includeMentor = false;

    /** A readable description of the audience (AudienceService describeAudience). */
    private String label;
}
