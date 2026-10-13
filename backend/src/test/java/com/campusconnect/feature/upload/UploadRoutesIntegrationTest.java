package com.campusconnect.feature.upload;

import static org.assertj.core.api.Assertions.assertThat;

import com.campusconnect.support.IntegrationTest;
import com.campusconnect.support.Response;
import com.campusconnect.support.TestUser;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** /api/uploads/image: one image, checked by its bytes, stored locally in tests (no Cloudinary). */
class UploadRoutesIntegrationTest extends IntegrationTest {

    static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d, 'I', 'H', 'D', 'R'};

    TestUser student;

    @BeforeEach
    void seed() {
        student = data.student();
    }

    Response upload(TestUser user, String folder, MockMultipartFile file) {
        MockMultipartHttpServletRequestBuilder request = MockMvcRequestBuilders.multipart("/api/uploads/image" + (folder == null ? "" : "?folder=" + folder));
        if (file != null) {
            request.file(file);
        }
        if (user != null) {
            request.header("Authorization", "Bearer " + user.token());
        }
        return api.perform(request);
    }

    @Test
    void storesAnImageAndReturnsWhereItLives() {
        Response res = upload(student, "avatars", new MockMultipartFile("file", "me.png", "image/png", PNG));
        assertThat(res.status()).isEqualTo(201);
        assertThat(res.message()).isEqualTo("Image uploaded");
        assertThat(res.string("$.data.url")).contains("/uploads/avatars/").endsWith(".png");
        assertThat(res.string("$.data.type")).isEqualTo("image/png");
        assertThat((Integer) res.at("$.data.size")).isEqualTo(PNG.length);
    }

    @Test
    void refusesWhatIsNotAnAllowedImage() {
        assertThat(upload(student, "secrets", new MockMultipartFile("file", "me.png", "image/png", PNG)).message()).isEqualTo("Invalid upload target");
        Response text = upload(student, "feed", new MockMultipartFile("file", "fake.png", "image/png", "just some text pretending".getBytes(StandardCharsets.UTF_8)));
        assertThat(text.status()).isEqualTo(400);
        assertThat(text.message()).isEqualTo("Only JPEG, PNG or WebP images are allowed");
        assertThat(text.errorCode()).isEqualTo("UPLOAD_ERROR");
        assertThat(upload(student, "feed", null).message()).isEqualTo("An image file is required");

        byte[] huge = Arrays.copyOf(PNG, 5 * 1024 * 1024 + 1);
        Response tooLarge = upload(student, "feed", new MockMultipartFile("file", "big.png", "image/png", huge));
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.message()).isEqualTo("Image must be 5 MB or smaller");
    }

    @Test
    void needsASignedInUser() {
        assertThat(upload(null, "avatars", new MockMultipartFile("file", "me.png", "image/png", PNG)).status()).isEqualTo(401);
    }
}
