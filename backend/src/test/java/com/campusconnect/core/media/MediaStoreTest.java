package com.campusconnect.core.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.domain.common.MediaKind;
import com.cloudinary.Cloudinary;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** Port of tests/unit/storyMedia.test.js and recruitmentMedia.test.js (no network: signatures only). */
class MediaStoreTest {

    static final String CLUB = "64b000000000000000000001";
    static final String USER = "64b000000000000000000002";

    static AppProperties properties(boolean cloudinary) {
        AppProperties properties = new AppProperties();
        properties.getJwt().setAccessSecret("test-access-secret-that-is-long-enough-for-hs256");
        if (cloudinary) {
            properties.getCloudinary().setCloudName("demo-campus");
            properties.getCloudinary().setApiKey("key123");
            properties.getCloudinary().setApiSecret("shh-secret");
        }
        properties.getCloudinary().setFolder("campusconnect");
        try {
            properties.getUploads().setDir(Files.createTempDirectory("cc-media-test").toString());
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
        return properties;
    }

    static MediaStores stores(boolean cloudinary) {
        AppProperties properties = properties(cloudinary);
        return new MediaStores(properties, new CloudinarySigner(properties), new Cloudinary(Map.of("cloud_name", "demo-campus")));
    }

    static String sha1(String text) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Nested
    class StoryMediaOnCloudinary {

        final MediaStore media = stores(true).story();

        Map<String, Object> cloudinaryResponse(MediaStore.UploadTicket ticket, Map<String, Object> overrides) {
            long version = 1727000000L;
            String publicId = (String) ticket.fields().get("public_id");
            Map<String, Object> response = new HashMap<>();
            response.put("provider", "cloudinary");
            response.put("kind", ticket.kind().name());
            response.put("publicId", publicId);
            response.put("version", version);
            response.put("signature", sha1("public_id=" + publicId + "&version=" + version + "shh-secret"));
            response.put("format", ticket.kind() == MediaKind.VIDEO ? "mp4" : "jpg");
            response.put("width", 1080);
            response.put("height", 1920);
            response.put("duration", 14.2);
            response.put("bytes", 2048);
            response.putAll(overrides);
            return response;
        }

        @Test
        void theBrowserGetsASignedTicketToUploadStraightToCloudinary() {
            MediaStore.UploadTicket ticket = media.createUploadTicket(CLUB, USER, MediaKind.VIDEO, null);
            assertThat(ticket.provider()).isEqualTo("cloudinary");
            assertThat(ticket.uploadUrl()).isEqualTo("https://api.cloudinary.com/v1_1/demo-campus/video/upload");
            assertThat(ticket.fields().get("api_key")).isEqualTo("key123");
            assertThat((String) ticket.fields().get("public_id")).matches("^campusconnect/stories/" + CLUB + "/[a-f0-9]{16}_[a-f0-9]{20}$");
            assertThat(ticket.fields().get("allowed_formats")).isEqualTo("mp4,mov,webm");
            assertThat(ticket.fields().get("eager")).isEqualTo("c_limit,h_1280,w_720,q_auto,du_30/mp4|so_0,c_limit,h_1280,w_720,q_auto/jpg");

            Map<String, Object> signed = new TreeMap<>(ticket.fields());
            signed.remove("api_key");
            Object signature = signed.remove("signature");
            String expected = sha1(signed.entrySet().stream().map(entry -> entry.getKey() + "=" + entry.getValue()).collect(Collectors.joining("&")) + "shh-secret");
            assertThat(signature).isEqualTo(expected);
            assertThat(ticket.toString()).doesNotContain("shh-secret");
        }

        @Test
        void aGenuineCloudinaryResponseIsAcceptedAndStoredAsAReferenceOnly() {
            MediaStore.UploadTicket ticket = media.createUploadTicket(CLUB, USER, MediaKind.VIDEO, null);
            MediaStore.VerifiedMedia stored = media.verifyUpload(cloudinaryResponse(ticket, Map.of("duration", 95)), CLUB, USER);
            assertThat(stored.provider()).isEqualTo("cloudinary");
            assertThat(stored.kind()).isEqualTo(MediaKind.VIDEO);
            assertThat(stored.key()).isEqualTo(ticket.fields().get("public_id"));
            assertThat(stored.version()).isEqualTo(1727000000L);
            assertThat(stored.duration()).isEqualTo(30.0);
        }

        @Test
        void forgedOrReusedCloudinaryResponsesAreRejected() {
            MediaStore.UploadTicket ticket = media.createUploadTicket(CLUB, USER, MediaKind.IMAGE, null);
            Map<String, Object> genuine = cloudinaryResponse(ticket, Map.of());

            assertThatThrownBy(() -> media.verifyUpload(with(genuine, "signature", "0".repeat(40)), CLUB, USER)).hasMessage("The upload could not be verified");
            assertThatThrownBy(() -> media.verifyUpload(with(genuine, "version", 1727000001L), CLUB, USER)).hasMessage("The upload could not be verified");
            assertThatThrownBy(() -> media.verifyUpload(genuine, CLUB, "64b000000000000000000009")).hasMessage("This upload was not issued for this club");
            assertThatThrownBy(() -> media.verifyUpload(genuine, "64b000000000000000000008", USER)).hasMessage("This upload was not issued for this club");
            assertThatThrownBy(() -> media.verifyUpload(with(genuine, "format", "svg"), CLUB, USER)).hasMessage("Unsupported file format");
        }

        @Test
        void viewersGetCdnUrlsWithSizeAndQualityOptimisations() {
            MediaStore.MediaUrls image = media.mediaUrls(MediaKind.IMAGE, "cloudinary", "campusconnect/stories/c/a_b", 7);
            assertThat(image.url()).isEqualTo("https://res.cloudinary.com/demo-campus/image/upload/c_limit,h_1920,w_1080,q_auto,f_auto/v7/campusconnect/stories/c/a_b");
            assertThat(image.thumb()).isEqualTo("https://res.cloudinary.com/demo-campus/image/upload/c_fill,h_200,w_200,q_auto,f_auto/v7/campusconnect/stories/c/a_b");

            MediaStore.MediaUrls video = media.mediaUrls(MediaKind.VIDEO, "cloudinary", "campusconnect/stories/c/a_b", 7);
            assertThat(video.url()).isEqualTo("https://res.cloudinary.com/demo-campus/video/upload/c_limit,h_1280,w_720,q_auto,du_30/v7/campusconnect/stories/c/a_b.mp4");
            assertThat(video.poster()).isEqualTo("https://res.cloudinary.com/demo-campus/video/upload/so_0,c_limit,h_1280,w_720,q_auto/v7/campusconnect/stories/c/a_b.jpg");

            MediaStore.MediaUrls shared = media.mediaUrls(MediaKind.IMAGE, "event", "https://res.cloudinary.com/demo-campus/image/upload/v1/campusconnect/event-posters/p.jpg", null);
            assertThat(shared.thumb()).isEqualTo("https://res.cloudinary.com/demo-campus/image/upload/c_fill,h_200,w_200,q_auto,f_auto/v1/campusconnect/event-posters/p.jpg");
            assertThat(media.mediaUrls(MediaKind.IMAGE, "event", "https://example.com/p.jpg", null).url()).isEqualTo("https://example.com/p.jpg");
        }

        @Test
        void videoFilesAreRecognisedFromTheirBytes() {
            assertThat(MediaTypes.detectVideo(bytes(new byte[] {0, 0, 0, 0x18}, "ftypisom", 8)).ext()).isEqualTo("mp4");
            assertThat(MediaTypes.detectVideo(bytes(new byte[] {0, 0, 0, 0x14}, "ftypqt  ", 8)).ext()).isEqualTo("mov");
            assertThat(MediaTypes.detectVideo(bytes(new byte[] {0x1a, 0x45, (byte) 0xdf, (byte) 0xa3}, "", 12)).ext()).isEqualTo("webm");
            assertThat(MediaTypes.detectVideo("hello world, not a video".getBytes(StandardCharsets.US_ASCII))).isNull();
        }
    }

    @Nested
    class RecruitmentFiles {

        static final String DRIVE = "64b000000000000000000001";

        @Test
        void pdfUploadsGetAPdfPublicIdThatIsStillTiedToTheDriveAndTheStudent() {
            MediaStore media = stores(true).recruitment();
            String id = media.issuePublicId(DRIVE, USER, MediaKind.DOCUMENT, "pdf");
            assertThat(id).matches(".*/recruitment/64b000000000000000000001/[a-f0-9]+_[a-f0-9]+\\.pdf$");
            assertThat(media.publicIdIssuedTo(id, DRIVE, USER)).isTrue();
            assertThat(media.publicIdIssuedTo(id, DRIVE, "64b000000000000000000009")).isFalse();
            assertThat(media.publicIdIssuedTo(id.replace(".pdf", "x.pdf"), DRIVE, USER)).isFalse();
        }

        @Test
        void pdfsAreLinkedThroughASignedDownloadThatExpiresPhotosUseTheCdn() {
            MediaStore media = stores(true).recruitment();
            URI pdf = URI.create(media.mediaUrls(MediaKind.DOCUMENT, "cloudinary", "campusconnect/recruitment/d/a_b.pdf", 3).url());
            Map<String, String> query = new LinkedHashMap<>();
            for (String pair : pdf.getRawQuery().split("&")) {
                String[] parts = pair.split("=", 2);
                query.put(parts[0], java.net.URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
            }
            assertThat(pdf.getPath()).endsWith("/raw/download");
            assertThat(query.get("public_id")).isEqualTo("campusconnect/recruitment/d/a_b.pdf");
            assertThat(query.get("type")).isEqualTo("upload");
            assertThat(Long.parseLong(query.get("expires_at")) - Long.parseLong(query.get("timestamp"))).isEqualTo(3600);
            assertThat(query.get("signature")).matches("^[a-f0-9]{40}$");

            String photo = media.mediaUrls(MediaKind.IMAGE, "cloudinary", "campusconnect/recruitment/d/c_d", 3).url();
            assertThat(photo).matches(".*/image/upload/.+/v3/campusconnect/recruitment/d/c_d$");
        }

        @Test
        void onlyPdfsAndPhotosAreAcceptedAndPdfsAreRecognisedFromTheirBytes() {
            MediaStore media = stores(false).recruitment();
            MediaStore.UploadTicket ticket = media.createUploadTicket(DRIVE, USER, MediaKind.DOCUMENT, null);
            assertThat(ticket.provider()).isEqualTo("local");
            assertThat(ticket.kind()).isEqualTo(MediaKind.DOCUMENT);
            assertThat(ticket.uploadUrl()).isEqualTo("/recruitment/" + DRIVE + "/media");
            assertThat(ticket.maxBytes()).isEqualTo(5 * 1024 * 1024);

            assertThatThrownBy(() -> media.saveLocalFile(bytes(new byte[] {0, 0, 0, 0x18}, "ftypisom", 16), "clip.mp4", DRIVE, USER, null, null))
                    .hasMessage("Only JPEG, PNG or WebP photos and PDF files are allowed");
            MediaStore.LocalUpload saved = media.saveLocalFile(bytes("%PDF-1.5\n".getBytes(StandardCharsets.US_ASCII), "", 32), "cv.pdf", DRIVE, USER, null, null);
            assertThat(saved.kind()).isEqualTo(MediaKind.DOCUMENT);
            assertThat(saved.format()).isEqualTo("pdf");
        }
    }

    static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }

    static byte[] bytes(byte[] head, String ascii, int zeros) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(head);
        out.writeBytes(ascii.getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[zeros]);
        return out.toByteArray();
    }
}
