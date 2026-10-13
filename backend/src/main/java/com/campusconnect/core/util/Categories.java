package com.campusconnect.core.util;

import java.util.List;
import java.util.stream.Stream;

/** Club and event categories (constants/Categories.js). HACKATHON events get the hackathon tools. */
public final class Categories {

    public static final List<String> CLUB_CATEGORIES = List.of(
            "TECHNOLOGY", "SPORTS", "CULTURAL", "LITERARY", "MUSIC", "ART", "SOCIAL_SERVICE", "ENTREPRENEURSHIP", "OTHER");

    public static final List<String> EVENT_CATEGORIES = Stream.concat(
                    CLUB_CATEGORIES.stream().filter(c -> !c.equals("OTHER")),
                    Stream.of("WORKSHOP", "SEMINAR", "COMPETITION", "HACKATHON", "OTHER"))
            .toList();

    private Categories() {
    }
}
