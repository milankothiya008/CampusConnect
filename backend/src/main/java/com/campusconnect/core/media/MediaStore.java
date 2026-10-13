package com.campusconnect.core.media;

import com.campusconnect.domain.common.MediaFile;
import com.campusconnect.domain.common.MediaKind;
import com.campusconnect.domain.common.StoredMedia;
import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.util.Hmacs;
import com.cloudinary.Cloudinary;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.json.JsonMapper;

/**
 * Photo, video, voice-note and document storage shared by stories, galleries, chat and recruitment
 * (services/MediaStore.js). Production uses Cloudinary: the browser uploads straight to it with a short-lived
 * signature from this API, so media bytes never pass through this server. Without Cloudinary credentials files go
 * to the uploads folder (development). Every upload is issued to one user for one scope (club, event, chat, drive)
 * and can only be attached there by that user.
 */
@Slf4j
public class MediaStore {

    public static final Map<MediaKind, List<String>> FORMATS = Map.of(
            MediaKind.IMAGE, List.of("jpg", "jpeg", "png", "webp"),
            MediaKind.VIDEO, List.of("mp4", "mov", "webm"),
            MediaKind.AUDIO, List.of("webm", "ogg", "m4a", "mp4", "mp3", "wav", "aac"),
            MediaKind.DOCUMENT, List.of("pdf"));
    /** Documents are "raw" files (free accounts block PDFs in the image pipeline); audio uses the video pipeline. */
    private static final Map<MediaKind, String> RESOURCE = Map.of(MediaKind.IMAGE, "image", MediaKind.VIDEO, "video", MediaKind.AUDIO, "video", MediaKind.DOCUMENT, "raw");
    public static final int TICKET_TTL_SECONDS = 60 * 60;
    private static final Pattern CLOUDINARY_IMAGE = Pattern.compile("^(https://res\\.cloudinary\\.com/[^/]+/image/upload/)(.+)$");
    private static final JsonMapper JSON = JsonMapper.builder().build();

    public record UploadTicket(String provider, MediaKind kind, String uploadUrl, Map<String, Object> fields, long maxBytes, int maxVideoSeconds, int expiresIn) {
    }

    public record LocalUpload(String token, MediaKind kind, long bytes, String format) {
    }

    /** A verified upload, ready to store on a document. */
    public record VerifiedMedia(MediaKind kind, String provider, String key, Long version, String format, Integer width, Integer height, Double duration, Long bytes) {

        /** As stored on gallery items and stories. */
        public MediaFile toMediaFile() {
            MediaFile file = new MediaFile();
            file.setKind(kind);
            file.setProvider(provider);
            file.setKey(key);
            file.setVersion(version);
            file.setFormat(format);
            file.setWidth(width);
            file.setHeight(height);
            file.setDuration(duration);
            file.setBytes(bytes);
            return file;
        }
    }

    public record MediaUrls(String url, String poster, String thumb) {
    }

    private final MediaStoreConfig config;
    private final AppProperties properties;
    private final CloudinarySigner signer;
    private final Cloudinary cloudinary;

    public MediaStore(MediaStoreConfig config, AppProperties properties, CloudinarySigner signer, Cloudinary cloudinary) {
        this.config = config;
        this.properties = properties;
        this.signer = signer;
        this.cloudinary = cloudinary;
    }

    public MediaStoreConfig config() {
        return config;
    }

    private boolean useCloudinary() {
        return properties.getCloudinary().isConfigured();
    }

    public String providerName() {
        return useCloudinary() ? "cloudinary" : "local";
    }

    public long maxBytesFor(MediaKind kind) {
        MediaStoreConfig.Limits limits = config.limits().get();
        return switch (kind) {
            case VIDEO -> limits.maxVideoBytes();
            case AUDIO -> limits.maxAudioBytes() > 0 ? limits.maxAudioBytes() : limits.maxVideoBytes();
            case DOCUMENT -> limits.maxDocumentBytes() > 0 ? limits.maxDocumentBytes() : limits.maxImageBytes();
            case IMAGE -> limits.maxImageBytes();
        };
    }

    private static AppException uploadError(String message) {
        return AppException.badRequest(message, ErrorCode.UPLOAD_ERROR);
    }

    private AppException notIssued() {
        return uploadError("This upload was not issued for this " + config.scopeLabel());
    }

    private String docExt(String ext) {
        String wanted = (ext == null || ext.isBlank() ? "pdf" : ext).toLowerCase(Locale.ROOT);
        if (!config.documentFormats().contains(wanted)) {
            throw uploadError("Only " + config.documentFormats().stream().map(format -> format.toUpperCase(Locale.ROOT)).collect(Collectors.joining(", ")) + " files are allowed");
        }
        return wanted;
    }

    private String acceptedLabel() {
        List<String> parts = new java.util.ArrayList<>();
        if (config.kinds().contains(MediaKind.IMAGE)) {
            parts.add("JPEG, PNG or WebP photos");
        }
        if (config.kinds().contains(MediaKind.VIDEO)) {
            parts.add("MP4, MOV or WebM videos");
        }
        if (config.kinds().contains(MediaKind.AUDIO)) {
            parts.add("voice notes");
        }
        if (config.kinds().contains(MediaKind.DOCUMENT)) {
            parts.add(config.documentFormats().stream().map(format -> format.toUpperCase(Locale.ROOT)).collect(Collectors.joining(", ")) + " files");
        }
        return parts.size() > 1 ? String.join(", ", parts.subList(0, parts.size() - 1)) + " and " + parts.get(parts.size() - 1) : parts.get(0);
    }

    // ------------------------------------------------------------ Cloudinary

    /** The public id carries a MAC of (scope, user, nonce): only that person can attach it, only in that scope. */
    private String idMac(String scopeId, String userId, String nonce) {
        return Hmacs.hmacHex(properties.getJwt().getAccessSecret() + ":" + config.macPurpose(), scopeId + ":" + userId + ":" + nonce).substring(0, 20);
    }

    String issuePublicId(String scopeId, String userId, MediaKind kind, String ext) {
        String nonce = Hmacs.randomHex(8);
        return config.folder().apply(scopeId) + "/" + nonce + "_" + idMac(scopeId, userId, nonce) + (kind == MediaKind.DOCUMENT ? "." + ext : "");
    }

    boolean publicIdIssuedTo(String publicId, String scopeId, String userId) {
        String prefix = config.folder().apply(scopeId) + "/";
        if (publicId == null || !publicId.startsWith(prefix)) {
            return false;
        }
        String[] parts = publicId.substring(prefix.length()).replaceAll("\\.[a-z0-9]{2,5}$", "").split("_", -1);
        return parts.length == 2 && !parts[0].isEmpty() && !parts[1].isEmpty() && Hmacs.safeEquals(parts[1], idMac(scopeId, userId, parts[0]));
    }

    private UploadTicket cloudinaryTicket(String scopeId, String userId, MediaKind kind, String ext) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("public_id", issuePublicId(scopeId, userId, kind, kind == MediaKind.DOCUMENT ? docExt(ext) : null));
        params.put("tags", config.tag());
        params.put("timestamp", Instant.now().getEpochSecond());
        // Cloudinary can't check the format of raw files; documents are checked by extension when attached.
        if (kind != MediaKind.DOCUMENT) {
            params.put("allowed_formats", String.join(",", FORMATS.get(kind)));
        }
        if (kind == MediaKind.VIDEO) {
            // Derivatives are generated at upload so the first viewer doesn't wait for transcoding.
            params.put("eager", config.transforms().video().get() + "/mp4|" + config.transforms().videoPoster() + "/jpg");
            params.put("eager_async", "true");
        }
        Map<String, Object> fields = new LinkedHashMap<>(params);
        fields.put("api_key", properties.getCloudinary().getApiKey());
        fields.put("signature", signer.sign(params));
        return new UploadTicket("cloudinary", kind,
                "https://api.cloudinary.com/v1_1/" + properties.getCloudinary().getCloudName() + "/" + RESOURCE.get(kind) + "/upload",
                fields, maxBytesFor(kind), config.limits().get().maxVideoSeconds(), TICKET_TTL_SECONDS);
    }

    private Double clampDuration(MediaKind kind, Object duration) {
        MediaStoreConfig.Limits limits = config.limits().get();
        double value = number(duration);
        if (kind == MediaKind.VIDEO) {
            return Math.min(value > 0 ? value : limits.maxVideoSeconds(), limits.maxVideoSeconds());
        }
        if (kind == MediaKind.AUDIO) {
            int cap = limits.maxAudioSeconds() > 0 ? limits.maxAudioSeconds() : 600;
            double seconds = Math.min(value > 0 ? value : 0, cap);
            return seconds > 0 ? seconds : null;
        }
        return null;
    }

    /** Cloudinary signs public_id + version with our secret, so a forged or altered response is rejected. */
    private VerifiedMedia verifyCloudinaryUpload(Map<String, Object> media, String scopeId, String userId) {
        MediaKind kind = kindOf(media.get("kind"));
        String publicId = media.get("publicId") instanceof String value ? value : null;
        double version = number(media.get("version"));
        if (kind == null || !config.kinds().contains(kind) || version <= 0 || version != Math.floor(version)) {
            throw uploadError("Upload details are incomplete");
        }
        if (!publicIdIssuedTo(publicId, scopeId, userId)) {
            throw notIssued();
        }
        long numericVersion = (long) version;
        if (!Hmacs.safeEquals(String.valueOf(media.get("signature")), signer.sign(Map.of("public_id", publicId, "version", numericVersion)))) {
            throw uploadError("The upload could not be verified");
        }
        String format = media.get("format") == null ? null : String.valueOf(media.get("format")).toLowerCase(Locale.ROOT);
        String extension = null;
        if (kind == MediaKind.DOCUMENT) {
            Matcher match = Pattern.compile("\\.([a-z0-9]{2,5})$").matcher(publicId);
            extension = match.find() ? match.group(1) : null;
            if (extension == null || !config.documentFormats().contains(extension)) {
                throw uploadError("Unsupported file format");
            }
        } else if (format != null && !format.isEmpty() && !FORMATS.get(kind).contains(format)) {
            throw uploadError("Unsupported file format");
        }
        return new VerifiedMedia(kind, "cloudinary", publicId, numericVersion,
                kind == MediaKind.DOCUMENT ? extension : (format == null || format.isEmpty() ? null : format),
                positiveInt(media.get("width")), positiveInt(media.get("height")), clampDuration(kind, media.get("duration")), positiveLong(media.get("bytes")));
    }

    private void destroyCloudinary(MediaKind kind, String key) throws IOException {
        Map<?, ?> result = cloudinary.uploader().destroy(key, Map.of("resource_type", RESOURCE.getOrDefault(kind, "image"), "invalidate", true));
        Object outcome = result.get("result");
        // "not found" means it is already gone, which is what we want.
        if (!"ok".equals(outcome) && !"not found".equals(outcome)) {
            throw new IOException("Cloudinary destroy failed (" + outcome + ")");
        }
    }

    // ------------------------------------------------------------ Local storage (development)

    private String signLocal(String payload) {
        return Hmacs.hmacBase64Url(properties.getJwt().getAccessSecret() + ":" + config.localPurpose(), payload);
    }

    private String localToken(Map<String, Object> details) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsString(details).getBytes(StandardCharsets.UTF_8));
        return payload + "." + signLocal(payload);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readLocalToken(Object token) {
        String[] parts = String.valueOf(token == null ? "" : token).split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty() || !Hmacs.safeEquals(parts[1], signLocal(parts[0]))) {
            return null;
        }
        try {
            return JSON.readValue(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8), Map.class);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private UploadTicket localTicket(String scopeId, MediaKind kind) {
        return new UploadTicket("local", kind, config.localUploadUrl().apply(scopeId), Map.of(), maxBytesFor(kind), config.limits().get().maxVideoSeconds(), TICKET_TTL_SECONDS);
    }

    /**
     * Saves a file posted to this API (development). {@code hintKind}/{@code hintExt} settle look-alikes: a WebM voice
     * note vs a WebM video, an Office file vs any ZIP.
     */
    public LocalUpload saveLocalFile(byte[] bytes, String originalName, String scopeId, String userId, MediaKind hintKind, String hintExt) {
        if (bytes == null || bytes.length == 0) {
            throw uploadError("A photo or video is required");
        }
        String nameExt = originalName != null && originalName.contains(".") ? originalName.substring(originalName.lastIndexOf('.') + 1) : "";
        String ext = (hintExt != null && !hintExt.isBlank() ? hintExt : nameExt).toLowerCase(Locale.ROOT);
        MediaTypes.Detected image = config.kinds().contains(MediaKind.IMAGE) ? MediaTypes.detectImage(bytes) : null;
        MediaTypes.Detected audio = image == null && config.kinds().contains(MediaKind.AUDIO) && hintKind == MediaKind.AUDIO ? MediaTypes.detectAudio(bytes) : null;
        MediaTypes.Detected video = image == null && audio == null && config.kinds().contains(MediaKind.VIDEO) ? MediaTypes.detectVideo(bytes) : null;
        MediaTypes.Detected document = image == null && audio == null && video == null && config.kinds().contains(MediaKind.DOCUMENT) ? MediaTypes.detectDocument(bytes, ext) : null;
        MediaTypes.Detected detected = image != null ? image : audio != null ? audio : video != null ? video
                : document != null && config.documentFormats().contains(document.ext()) ? document : null;
        if (detected == null) {
            throw uploadError("Only " + acceptedLabel() + " are allowed");
        }
        MediaKind kind = image != null ? MediaKind.IMAGE : audio != null ? MediaKind.AUDIO : video != null ? MediaKind.VIDEO : MediaKind.DOCUMENT;
        long max = maxBytesFor(kind);
        if (bytes.length > max) {
            String noun = switch (kind) {
                case VIDEO -> "Videos";
                case AUDIO -> "Voice notes";
                case DOCUMENT -> "Files";
                case IMAGE -> "Photos";
            };
            throw AppException.of(413, noun + " must be " + Math.round(max / (1024d * 1024d)) + " MB or smaller", ErrorCode.UPLOAD_ERROR);
        }
        String relative = config.localDir() + "/" + scopeId + "/" + System.currentTimeMillis() + "-" + Hmacs.randomHex(8) + "." + detected.ext();
        try {
            Path target = Path.of(properties.getUploads().getDir()).resolve(relative);
            Files.createDirectories(target.getParent());
            Files.write(target, bytes);
        } catch (IOException e) {
            throw AppException.of(500, "Upload failed", ErrorCode.UPLOAD_ERROR);
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("k", relative);
        details.put("t", kind.name());
        details.put("f", detected.ext());
        details.put("b", bytes.length);
        details.put("c", scopeId);
        details.put("u", userId);
        details.put("x", System.currentTimeMillis() + TICKET_TTL_SECONDS * 1000L);
        return new LocalUpload(localToken(details), kind, bytes.length, detected.ext());
    }

    private VerifiedMedia verifyLocalUpload(Map<String, Object> media, String scopeId, String userId) {
        Map<String, Object> details = readLocalToken(media.get("token"));
        if (details == null || !scopeId.equals(String.valueOf(details.get("c"))) || !userId.equals(String.valueOf(details.get("u")))) {
            throw notIssued();
        }
        if (number(details.get("x")) < System.currentTimeMillis()) {
            throw uploadError("This upload has expired — please add the file again");
        }
        String key = String.valueOf(details.get("k"));
        if (!key.startsWith(config.localDir() + "/" + scopeId + "/") || key.contains("..")) {
            throw uploadError("Invalid upload");
        }
        MediaKind kind = kindOf(details.get("t"));
        return new VerifiedMedia(kind, "local", key, null, String.valueOf(details.get("f")), positiveInt(media.get("width")), positiveInt(media.get("height")),
                clampDuration(kind, media.get("duration")), (long) number(details.get("b")));
    }

    private String localUrl(String key) {
        return properties.getPublicApiUrl() + "/uploads/" + key;
    }

    /**
     * Documents are fetched through Cloudinary's signed download API: plain PDF delivery is blocked on free
     * accounts, and a link that expires after an hour keeps resumes private to whoever was shown it.
     */
    private String documentUrl(String key) {
        long timestamp = Instant.now().getEpochSecond();
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("public_id", key);
        params.put("timestamp", timestamp);
        params.put("type", "upload");
        params.put("expires_at", timestamp + 3600);
        Map<String, Object> query = new LinkedHashMap<>(params);
        query.put("api_key", properties.getCloudinary().getApiKey());
        query.put("signature", signer.sign(params));
        String encoded = query.entrySet().stream()
                .map(entry -> URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8) + "=" + URLEncoder.encode(String.valueOf(entry.getValue()), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
        return "https://api.cloudinary.com/v1_1/" + properties.getCloudinary().getCloudName() + "/raw/download?" + encoded;
    }

    private String cloudinaryUrl(String resource, String transform, Number version, String publicId, String extension) {
        return "https://res.cloudinary.com/" + properties.getCloudinary().getCloudName() + "/" + resource + "/upload/" + transform + "/v" + (version == null ? 1 : version.longValue()) + "/" + publicId + extension;
    }

    /** Adds a transformation to an existing Cloudinary delivery URL (e.g. an event poster); other URLs unchanged. */
    public static String transformUrl(String url, String transform) {
        Matcher match = CLOUDINARY_IMAGE.matcher(String.valueOf(url == null ? "" : url));
        return match.matches() ? match.group(1) + transform + "/" + match.group(2) : url;
    }

    // ------------------------------------------------------------ Shared

    public UploadTicket createUploadTicket(String scopeId, String userId, MediaKind kind, String ext) {
        if (kind == MediaKind.DOCUMENT) {
            docExt(ext);
        }
        return useCloudinary() ? cloudinaryTicket(scopeId, userId, kind, ext) : localTicket(scopeId, kind);
    }

    /** Checks the upload details the browser sends when attaching a file ({@code provider} cloudinary or local). */
    public VerifiedMedia verifyUpload(Map<String, Object> media, String scopeId, String userId) {
        Object provider = media == null ? null : media.get("provider");
        if ("cloudinary".equals(provider)) {
            if (!useCloudinary()) {
                throw uploadError("Cloud media storage is not configured");
            }
            return verifyCloudinaryUpload(media, scopeId, userId);
        }
        if ("local".equals(provider)) {
            return verifyLocalUpload(media, scopeId, userId);
        }
        throw uploadError("Unknown media storage");
    }

    /** Viewer-facing URLs, built from the stored details on every read (no lookups). */
    public MediaUrls mediaUrls(MediaKind kind, String provider, String key, Number version) {
        MediaStoreConfig.Transforms transforms = config.transforms();
        if (kind == MediaKind.DOCUMENT) {
            return new MediaUrls("cloudinary".equals(provider) ? documentUrl(key) : localUrl(key), null, null);
        }
        if (kind == MediaKind.AUDIO) {
            return new MediaUrls("cloudinary".equals(provider)
                    ? cloudinaryUrl("video", transforms.audio() != null ? transforms.audio() : "q_auto", version, key, ".mp3")
                    : localUrl(key), null, null);
        }
        if ("cloudinary".equals(provider)) {
            if (kind == MediaKind.VIDEO) {
                return new MediaUrls(
                        cloudinaryUrl("video", transforms.video().get(), version, key, ".mp4"),
                        cloudinaryUrl("video", transforms.videoPoster(), version, key, ".jpg"),
                        cloudinaryUrl("video", "so_0," + transforms.thumb(), version, key, ".jpg"));
            }
            return new MediaUrls(cloudinaryUrl("image", transforms.image(), version, key, ""), null, cloudinaryUrl("image", transforms.thumb(), version, key, ""));
        }
        if ("event".equals(provider)) {
            return new MediaUrls(transformUrl(key, transforms.image()), null, transformUrl(key, transforms.thumb()));
        }
        String url = localUrl(key);
        return new MediaUrls(url, null, kind == MediaKind.IMAGE ? url : null);
    }

    public MediaUrls mediaUrls(StoredMedia media) {
        return mediaUrls(media.getKind(), media.getProvider(), media.getKey(), media.getVersion());
    }

    public boolean deleteMediaQuietly(StoredMedia media) {
        return media == null || deleteMediaQuietly(media.getKind(), media.getProvider(), media.getKey());
    }

    /** Removes a file from storage. Event posters shared into stories belong to the event and are kept. */
    public void deleteMedia(MediaKind kind, String provider, String key) throws IOException {
        if ("cloudinary".equals(provider)) {
            if (!useCloudinary()) {
                throw new IOException("Cloudinary is not configured, cannot remove " + config.name().toLowerCase(Locale.ROOT) + " media");
            }
            destroyCloudinary(kind, key);
        } else if ("local".equals(provider)) {
            if (key.startsWith(config.localDir() + "/") && !key.contains("..")) {
                Files.deleteIfExists(Path.of(properties.getUploads().getDir()).resolve(key));
            }
        }
    }

    public boolean deleteMediaQuietly(MediaKind kind, String provider, String key) {
        try {
            deleteMedia(kind, provider, key);
            return true;
        } catch (IOException | RuntimeException e) {
            log.warn("{} media cleanup failed ({} {}): {}", config.name(), provider, key, e.getMessage());
            return false;
        }
    }

    private static MediaKind kindOf(Object value) {
        try {
            return value == null ? null : MediaKind.valueOf(String.valueOf(value));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double number(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return value == null ? 0 : Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static Integer positiveInt(Object value) {
        double number = number(value);
        return number > 0 ? (int) Math.round(number) : null;
    }

    private static Long positiveLong(Object value) {
        double number = number(value);
        return number > 0 ? Math.round(number) : null;
    }
}
