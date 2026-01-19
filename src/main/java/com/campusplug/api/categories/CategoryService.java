package com.campusplug.api.categories;

import com.campusplug.api.cache.CacheConfig;
import com.campusplug.api.categories.dto.CategoryResponse;
import com.campusplug.api.listings.ListingRepository;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class CategoryService {

    private final ListingRepository listingRepository;

    public CategoryService(ListingRepository listingRepository) {
        this.listingRepository = listingRepository;
    }

    @Cacheable(cacheNames = CacheConfig.CATEGORIES_CACHE)
    public List<CategoryResponse> listCategories() {
        Map<String, Long> activeCounts = new java.util.HashMap<>();
        for (ListingRepository.CategoryCountProjection row : listingRepository.countActiveByCategory()) {
            activeCounts.put(row.getCategoryCode(), row.getActiveCount());
        }

        Map<CategoryCode, CategoryResponse> out = new EnumMap<>(CategoryCode.class);
        for (CategoryCode c : CategoryCode.values()) {
            long count = activeCounts.getOrDefault(c.name(), 0L);
            out.put(c, new CategoryResponse(
                    c.name(),
                    c.displayName(),
                    null,
                    count,
                    badgeFor(count)
            ));
        }

        // Avoid Stream#toList() (immutable list types) so cached values can be deserialized safely.
        return out.values().stream().collect(Collectors.toList());
    }

    private static String badgeFor(long activeCount) {
        if (activeCount >= 50) {
            return "HOT";
        }
        return null;
    }
}
