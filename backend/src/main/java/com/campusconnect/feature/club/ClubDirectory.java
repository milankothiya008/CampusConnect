package com.campusconnect.feature.club;

import com.campusconnect.core.cache.CacheNames;
import com.campusconnect.core.web.Pagination;
import com.campusconnect.domain.club.ClubStatus;
import java.util.List;
import java.util.Map;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Component;

/**
 * The public clubs directory is the same for everyone, so a page of it is shared by all replicas for a few seconds
 * (Node's clubDirectoryCache, now in Redis). Club, membership and recruitment-drive writes clear it
 * ({@link ClubCaches}). The admin's status notes never enter the shared copy.
 */
@Component
public class ClubDirectory {

    /** A cached page: the clubs and how many match in total. */
    public record DirectoryPage(List<Map<String, Object>> items, long total) {
    }

    private final ClubListing listing;

    public ClubDirectory(ClubListing listing) {
        this.listing = listing;
    }

    /** The cache key: skip, limit and the raw filters, like the Node key. */
    public static String key(Pagination pagination, String search, String category, String department) {
        return pagination.skip() + "|" + pagination.limit() + "|" + nonNull(search) + "|" + nonNull(category) + "|" + nonNull(department);
    }

    @Cacheable(cacheNames = CacheNames.CLUB_DIRECTORY, key = "#p0")
    public DirectoryPage page(String key, Pagination pagination, String search, String category, String department) {
        ClubListing.Loaded loaded = listing.load(ClubListing.filter(ClubStatus.ACTIVE.name(), search, category, department), pagination);
        loaded.items().forEach(item -> item.remove("statusNote"));
        return new DirectoryPage(loaded.items(), loaded.total());
    }

    private static String nonNull(String value) {
        return value == null ? "" : value;
    }
}
