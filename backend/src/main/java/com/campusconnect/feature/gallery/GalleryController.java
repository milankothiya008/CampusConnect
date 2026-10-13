package com.campusconnect.feature.gallery;

import com.campusconnect.core.security.AuthUser;
import com.campusconnect.core.security.OptionalAuth;
import com.campusconnect.core.web.ApiResponse;
import com.campusconnect.feature.gallery.GalleryDtos.Galleries;
import com.campusconnect.feature.gallery.GalleryDtos.GalleriesQuery;
import com.campusconnect.feature.gallery.GalleryDtos.GalleryCard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** /api/gallery (routes/GalleryRoutes.js): the Gallery section; each event's own gallery is under /api/events/{id}/gallery. */
@RestController
@RequestMapping("/api/gallery")
public class GalleryController {

    private final GalleryDirectory directory;

    public GalleryController(GalleryDirectory directory) {
        this.directory = directory;
    }

    @OptionalAuth
    @GetMapping
    public ApiResponse<List<GalleryCard>> list(@AuthenticationPrincipal AuthUser actor, @Valid @ModelAttribute GalleriesQuery query) {
        Galleries galleries = directory.listGalleries(actor, query);
        return ApiResponse.ok("Galleries fetched", galleries.items(), galleries.meta());
    }
}
