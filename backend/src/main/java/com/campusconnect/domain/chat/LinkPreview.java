package com.campusconnect.domain.chat;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A preview card for the first link in a message's text (Message.link), filled in after sending. */
@Getter
@Setter
@NoArgsConstructor
public class LinkPreview {

    private String url;
    private String title;
    private String description;
    private String image;
    private String site;
}
