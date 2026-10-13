package com.campusconnect.core.media;

import com.campusconnect.domain.common.MediaKind;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * One media store (MediaStore.createMediaStore config).
 *
 * @param name           label for logs ("Story", "Gallery")
 * @param scopeLabel     what an upload is issued for, used in errors ("club", "event")
 * @param folder         scope id -> Cloudinary folder
 * @param localDir       folder under the uploads directory
 * @param macPurpose     HMAC key suffix of issued public ids (keep stable: it signs uploads in flight)
 * @param localPurpose   HMAC key suffix of local upload tokens
 * @param localUploadUrl scope id -> API path the browser posts files to in development
 */
public record MediaStoreConfig(
        String name,
        String scopeLabel,
        Function<String, String> folder,
        String localDir,
        String macPurpose,
        String localPurpose,
        String tag,
        Supplier<Limits> limits,
        Transforms transforms,
        Function<String, String> localUploadUrl,
        Set<MediaKind> kinds,
        List<String> documentFormats) {

    public record Limits(long maxImageBytes, long maxVideoBytes, int maxVideoSeconds, long maxAudioBytes, int maxAudioSeconds, long maxDocumentBytes) {
    }

    /** Cloudinary delivery transformations. */
    public record Transforms(Supplier<String> video, String videoPoster, String image, String thumb, String audio) {
    }
}
