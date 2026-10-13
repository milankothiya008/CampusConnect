package com.campusconnect.feature.club;

import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.domain.club.SocialLinks;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A club's website and social profiles (utils/ClubLinks.js). Every link must be an http(s) address, and a social
 * link must point at its platform's own domain, so a "YouTube" button can never lead somewhere else.
 */
public final class ClubLinks {

    /** A social platform: its key in {@code Club.socialLinks}, the label used in messages and its domains. */
    public record Platform(String key, String label, List<String> hosts) {
    }

    public static final List<Platform> SOCIAL_PLATFORMS = List.of(
            new Platform("instagram", "Instagram", List.of("instagram.com")),
            new Platform("linkedin", "LinkedIn", List.of("linkedin.com", "lnkd.in")),
            new Platform("x", "X (Twitter)", List.of("x.com", "twitter.com")),
            new Platform("youtube", "YouTube", List.of("youtube.com", "youtu.be")),
            new Platform("facebook", "Facebook", List.of("facebook.com", "fb.com", "fb.me")),
            new Platform("github", "GitHub", List.of("github.com")),
            new Platform("discord", "Discord", List.of("discord.gg", "discord.com")),
            new Platform("whatsapp", "WhatsApp", List.of("chat.whatsapp.com", "whatsapp.com", "wa.me")));

    static final int MAX_URL_LENGTH = 300;
    private static final Pattern SCHEME = Pattern.compile("^[a-z][a-z0-9+.-]*:", Pattern.CASE_INSENSITIVE);

    private ClubLinks() {
    }

    private static AppException invalid(String message) {
        return AppException.badRequest(message, ErrorCode.VALIDATION_ERROR);
    }

    /** Accepts "instagram.com/club" as well as full URLs; returns the cleaned https(s) URL, or null when empty. */
    public static String normalizeUrl(Object value, String label, List<String> hosts) {
        String raw = JsValues.trimmed(value);
        if (raw.isEmpty()) {
            return null;
        }
        if (raw.length() > MAX_URL_LENGTH) {
            throw invalid(label + " link is too long");
        }
        String withScheme = SCHEME.matcher(raw).find() ? raw : "https://" + raw;
        WebUrl url = WebUrl.parse(withScheme);
        if (url == null || !url.isHttp() || !url.host().contains(".")) {
            throw invalid(label + " must be a valid web address");
        }
        if (url.credentials()) {
            throw invalid(label + " must not contain a username or password");
        }
        if (hosts != null && hosts.stream().noneMatch(host -> url.host().equals(host) || url.host().endsWith("." + host))) {
            throw invalid(label + " link must be on " + hosts.getFirst());
        }
        return url.toString();
    }

    /** Every platform's link, cleaned (null when not given); unknown platforms are refused. */
    public static Map<String, String> normalizeSocialLinks(Object links) {
        if (!(links instanceof Map<?, ?> given)) {
            throw invalid("Social links must be an object");
        }
        List<String> unknown = given.keySet().stream()
                .map(String::valueOf)
                .filter(key -> SOCIAL_PLATFORMS.stream().noneMatch(platform -> platform.key().equals(key)))
                .toList();
        if (!unknown.isEmpty()) {
            throw invalid("Unsupported social link: " + String.join(", ", unknown));
        }
        Map<String, String> cleaned = new LinkedHashMap<>();
        for (Platform platform : SOCIAL_PLATFORMS) {
            cleaned.put(platform.key(), normalizeUrl(given.get(platform.key()), platform.label(), platform.hosts()));
        }
        return cleaned;
    }

    /** The cleaned links as the stored sub-document. */
    public static SocialLinks toSocialLinks(Map<String, String> links) {
        SocialLinks social = new SocialLinks();
        social.setInstagram(links.get("instagram"));
        social.setLinkedin(links.get("linkedin"));
        social.setX(links.get("x"));
        social.setYoutube(links.get("youtube"));
        social.setFacebook(links.get("facebook"));
        social.setGithub(links.get("github"));
        social.setDiscord(links.get("discord"));
        social.setWhatsapp(links.get("whatsapp"));
        return social;
    }
}
