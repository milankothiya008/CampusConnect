package com.campusconnect.core.media;

import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.core.config.AppProperties;
import com.cloudinary.Cloudinary;
import java.util.EnumSet;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * The four media stores (StoryMediaService, GalleryMediaService, ChatMediaService, RecruitmentMediaService).
 * Keep the MAC purposes stable: they sign uploads that are in flight.
 */
@Component
public class MediaStores {

    public static final List<String> CHAT_DOCUMENT_FORMATS = List.of("pdf", "docx", "doc", "pptx", "ppt", "xlsx", "xls", "txt", "csv");

    private final MediaStore story;
    private final MediaStore gallery;
    private final MediaStore chat;
    private final MediaStore recruitment;

    public MediaStores(AppProperties properties, CloudinarySigner signer, Cloudinary cloudinary) {
        String root = properties.getCloudinary().getFolder();

        // Stories: portrait-first delivery sizes, videos trimmed to the story length. Issued per club.
        this.story = new MediaStore(new MediaStoreConfig("Story", "club", clubId -> root + "/stories/" + clubId, "stories", "story-media", "story-local", "campusconnect-story",
                () -> limits(properties.getStories()),
                new MediaStoreConfig.Transforms(() -> "c_limit,h_1280,w_720,q_auto,du_" + properties.getStories().getMaxVideoSeconds(),
                        "so_0,c_limit,h_1280,w_720,q_auto", "c_limit,h_1920,w_1080,q_auto,f_auto", "c_fill,h_200,w_200,q_auto,f_auto", null),
                clubId -> "/stories/media", EnumSet.of(MediaKind.IMAGE, MediaKind.VIDEO), List.of("pdf")), properties, signer, cloudinary);

        // Galleries keep landscape photos at full width; the grid uses square, subject-centred thumbnails. Per event.
        this.gallery = new MediaStore(new MediaStoreConfig("Gallery", "event", eventId -> root + "/gallery/" + eventId, "gallery", "gallery-media", "gallery-local", "campusconnect-gallery",
                () -> limits(properties.getGallery()),
                new MediaStoreConfig.Transforms(() -> "c_limit,h_1280,w_1280,q_auto,du_" + properties.getGallery().getMaxVideoSeconds(),
                        "so_0,c_limit,h_1280,w_1280,q_auto", "c_limit,h_2048,w_2048,q_auto,f_auto", "c_fill,g_auto,h_480,w_480,q_auto,f_auto", null),
                eventId -> "/events/" + eventId + "/gallery/media", EnumSet.of(MediaKind.IMAGE, MediaKind.VIDEO), List.of("pdf")), properties, signer, cloudinary);

        // Chat photos, videos, voice notes and documents, issued per conversation.
        AppProperties.Chat chatLimits = properties.getChat();
        this.chat = new MediaStore(new MediaStoreConfig("Chat", "chat", conversationId -> root + "/chat/" + conversationId, "chat", "chat-media", "chat-local", "campusconnect-chat",
                () -> new MediaStoreConfig.Limits(chatLimits.getMaxImageBytes(), chatLimits.getMaxVideoBytes(), chatLimits.getMaxVideoSeconds(),
                        chatLimits.getMaxAudioBytes(), chatLimits.getMaxAudioSeconds(), chatLimits.getMaxDocumentBytes()),
                new MediaStoreConfig.Transforms(() -> "c_limit,h_1280,w_1280,q_auto,du_" + chatLimits.getMaxVideoSeconds(),
                        "so_0,c_limit,h_1280,w_1280,q_auto", "c_limit,h_2048,w_2048,q_auto,f_auto", "c_fill,h_400,w_400,q_auto,f_auto", "q_auto"),
                conversationId -> "/chat/media?conversation=" + conversationId, EnumSet.allOf(MediaKind.class), CHAT_DOCUMENT_FORMATS), properties, signer, cloudinary);

        // Recruitment application files (resumes, portfolios): PDFs or photos, issued per drive.
        this.recruitment = new MediaStore(new MediaStoreConfig("Recruitment", "recruitment drive", driveId -> root + "/recruitment/" + driveId, "recruitment", "recruitment-media", "recruitment-local", "campusconnect-recruitment",
                () -> limits(properties.getRecruitment()),
                new MediaStoreConfig.Transforms(() -> "", "", "c_limit,h_2048,w_2048,q_auto,f_auto", "c_fill,h_240,w_240,q_auto,f_auto", null),
                driveId -> "/recruitment/" + driveId + "/media", EnumSet.of(MediaKind.DOCUMENT, MediaKind.IMAGE), List.of("pdf")), properties, signer, cloudinary);
    }

    private static MediaStoreConfig.Limits limits(AppProperties.MediaLimits limits) {
        return new MediaStoreConfig.Limits(limits.getMaxImageBytes(), limits.getMaxVideoBytes(), limits.getMaxVideoSeconds(), 0, 0, 0);
    }

    public MediaStore story() {
        return story;
    }

    public MediaStore gallery() {
        return gallery;
    }

    public MediaStore chat() {
        return chat;
    }

    public MediaStore recruitment() {
        return recruitment;
    }
}
