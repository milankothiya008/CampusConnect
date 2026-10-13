package com.campusconnect.feature.gallery;

import com.campusconnect.core.media.MediaStore;
import com.campusconnect.core.ratelimit.RateLimitPolicy;
import com.campusconnect.core.ratelimit.RateLimited;
import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.core.web.ObjectIds;
import com.campusconnect.domain.event.GalleryStatus;
import com.campusconnect.feature.gallery.GalleryDtos.AddBody;
import com.campusconnect.feature.gallery.GalleryDtos.Approved;
import com.campusconnect.feature.gallery.GalleryDtos.Gallery;
import com.campusconnect.feature.gallery.GalleryDtos.GalleryItem;
import com.campusconnect.feature.gallery.GalleryDtos.LocalMedia;
import com.campusconnect.feature.gallery.GalleryDtos.PageQuery;
import com.campusconnect.feature.gallery.GalleryDtos.Rejected;
import com.campusconnect.feature.gallery.GalleryDtos.Removed;
import com.campusconnect.feature.gallery.GalleryDtos.ReviewBody;
import com.campusconnect.feature.gallery.GalleryDtos.TicketsBody;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * An event's photo and video gallery, /api/events/{id}/gallery (the gallery routes of routes/EventRoutes.js).
 * Approved items are public; uploads and review need a verified account.
 */
@RestController
@RequestMapping("/api/events/{id}/gallery")
public class EventGalleryController {

    private final GalleryService gallery;

    public EventGalleryController(GalleryService gallery) {
        this.gallery = gallery;
    }

    @OptionalAuth
    @GetMapping
    public ApiResponse<Gallery> list(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @ModelAttribute PageQuery query) {
        GalleryService.GalleryPage page = gallery.getGallery(actor, ObjectIds.require(id, "id"), query.page(), query.limit());
        return ApiResponse.ok("Gallery fetched", page.gallery(), page.meta());
    }

    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/uploads")
    public ResponseEntity<ApiResponse<List<MediaStore.UploadTicket>>> uploadTickets(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @Valid @RequestBody TicketsBody body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Uploads ready", gallery.createUploadTickets(actor, ObjectIds.require(id, "id"), body.kinds())));
    }

    /** Development storage only: the browser posts the file here instead of to Cloudinary. */
    @RateLimited(RateLimitPolicy.UPLOAD)
    @PostMapping("/media")
    public ResponseEntity<ApiResponse<LocalMedia>> uploadLocal(@AuthenticationPrincipal AuthUser actor, @PathVariable String id,
            @RequestParam(value = "file", required = false) MultipartFile file) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok("Media uploaded", gallery.uploadLocalMedia(actor, ObjectIds.require(id, "id"), file)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<GalleryItem>> add(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody AddBody body) {
        GalleryItem item = gallery.addMedia(actor, ObjectIds.require(id, "id"), body.media());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(item.status() == GalleryStatus.APPROVED ? "Added to the gallery" : "Sent for review", item));
    }

    @PostMapping("/approve")
    public ApiResponse<Approved> approve(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody ReviewBody body) {
        Approved result = gallery.approveMedia(actor, ObjectIds.require(id, "id"), body.ids());
        return ApiResponse.ok(result.approved() == 1 ? "Approved — it's now in the gallery" : "Approved " + result.approved() + " — they're now in the gallery", result);
    }

    @PostMapping("/reject")
    public ApiResponse<Rejected> reject(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @Valid @RequestBody ReviewBody body) {
        Rejected result = gallery.rejectMedia(actor, ObjectIds.require(id, "id"), body.ids(), body.reason());
        return ApiResponse.ok(result.rejected() == 1 ? "Declined and deleted" : "Declined and deleted " + result.rejected(), result);
    }

    @DeleteMapping("/{mediaId}")
    public ApiResponse<Removed> remove(@AuthenticationPrincipal AuthUser actor, @PathVariable String id, @PathVariable String mediaId) {
        String eventId = ObjectIds.require(id, "id");
        return ApiResponse.ok("Deleted from the gallery", new Removed(gallery.removeMedia(actor, eventId, ObjectIds.require(mediaId, "mediaId"))));
    }
}
