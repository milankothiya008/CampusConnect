package com.campusconnect.core.media;

import com.campusconnect.core.config.AppProperties;
import com.campusconnect.core.error.AppException;
import com.campusconnect.core.error.ErrorCode;
import com.campusconnect.core.util.Hmacs;
import com.cloudinary.Cloudinary;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * Single images uploaded through the API — club logos and covers, event posters, feed images, avatars
 * (services/StorageService.js). Cloudinary when configured, otherwise the local uploads folder.
 */
@Slf4j
@Service
public class ImageStorage {

    public static final List<String> ALLOWED_FOLDERS = List.of("club-logos", "club-covers", "event-posters", "feed", "avatars");

    public record Stored(String url, String type, long size) {
    }

    private final AppProperties properties;
    private final Cloudinary cloudinary;

    public ImageStorage(AppProperties properties, Cloudinary cloudinary) {
        this.properties = properties;
        this.cloudinary = cloudinary;
    }

    public Stored storeImage(MultipartFile file, String folder) {
        if (file == null || file.isEmpty()) {
            throw AppException.badRequest("An image file is required", ErrorCode.VALIDATION_ERROR);
        }
        if (!ALLOWED_FOLDERS.contains(folder)) {
            throw AppException.badRequest("Invalid upload target", ErrorCode.VALIDATION_ERROR);
        }
        long max = properties.getUploads().getMaxBytes();
        if (file.getSize() > max) {
            throw AppException.of(413, "Image must be " + Math.round(max / (1024d * 1024d)) + " MB or smaller", ErrorCode.UPLOAD_ERROR);
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw AppException.badRequest("Invalid upload", ErrorCode.UPLOAD_ERROR);
        }
        MediaTypes.Detected image = MediaTypes.detectImage(bytes);
        if (image == null) {
            throw AppException.badRequest("Only JPEG, PNG or WebP images are allowed", ErrorCode.UPLOAD_ERROR);
        }
        String url = properties.getCloudinary().isConfigured() ? uploadToCloudinary(bytes, folder) : saveLocally(bytes, image, folder);
        return new Stored(url, image.mime(), bytes.length);
    }

    private String uploadToCloudinary(byte[] bytes, String folder) {
        try {
            Map<?, ?> result = cloudinary.uploader().upload(bytes, Map.of("folder", properties.getCloudinary().getFolder() + "/" + folder, "resource_type", "image"));
            Object url = result.get("secure_url");
            if (url == null) {
                throw new IOException("no secure_url");
            }
            return url.toString();
        } catch (IOException | RuntimeException e) {
            log.warn("Cloudinary image upload failed: {}", e.getMessage());
            throw AppException.of(502, "Image upload failed", ErrorCode.UPLOAD_ERROR);
        }
    }

    private String saveLocally(byte[] bytes, MediaTypes.Detected image, String folder) {
        try {
            Path dir = Path.of(properties.getUploads().getDir(), folder);
            Files.createDirectories(dir);
            String fileName = System.currentTimeMillis() + "-" + Hmacs.randomHex(8) + "." + image.ext();
            Files.write(dir.resolve(fileName), bytes);
            return properties.getPublicApiUrl() + "/uploads/" + folder + "/" + fileName;
        } catch (IOException e) {
            throw AppException.of(500, "Image upload failed", ErrorCode.UPLOAD_ERROR);
        }
    }
}
