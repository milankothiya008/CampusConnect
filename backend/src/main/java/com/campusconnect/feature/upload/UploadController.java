package com.campusconnect.feature.upload;

import com.campusconnect.core.media.ImageStorage;
import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.web.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * /api/uploads (routes/UploadRoutes.js): one image (club logo or cover, event poster, feed image, avatar) checked by
 * its bytes and stored in media storage; the client keeps the returned URL.
 */
@RestController
@RequestMapping("/api/uploads")
public class UploadController {

    private final ImageStorage images;

    public UploadController(ImageStorage images) {
        this.images = images;
    }

    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/image")
    public ResponseEntity<ApiResponse<ImageStorage.Stored>> uploadImage(@RequestParam(value = "file", required = false) MultipartFile file,
            @RequestParam(required = false) String folder) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Image uploaded", images.storeImage(file, folder == null ? "" : folder)));
    }
}
