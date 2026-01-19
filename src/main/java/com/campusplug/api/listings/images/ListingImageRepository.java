package com.campusplug.api.listings.images;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

public interface ListingImageRepository extends JpaRepository<ListingImageEntity, Long> {

    long countByListingId(Long listingId);

    List<ListingImageEntity> findByListingIdOrderByCreatedAtAsc(Long listingId);

    List<ListingImageEntity> findByListingIdInOrderByListingIdAscCreatedAtAsc(Collection<Long> listingIds);

    void deleteByIdAndListingId(Long id, Long listingId);
}
