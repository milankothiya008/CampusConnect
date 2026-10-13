package com.campusconnect.domain.club;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Club social profiles (keys from core.util.ClubLinks.SOCIAL_PLATFORMS); validated before saving. */
@Getter
@Setter
@NoArgsConstructor
public class SocialLinks {
    private String instagram;
    private String linkedin;
    private String x;
    private String youtube;
    private String facebook;
    private String github;
    private String discord;
    private String whatsapp;
}
