package com.campusplug.api.listings;

import com.campusplug.api.listings.dto.CreateListingRequest;
import com.campusplug.api.listings.dto.ListingResponse;
import com.campusplug.api.listings.dto.MyListingsResponse;
import com.campusplug.api.listings.dto.UpdateListingRequest;
import com.campusplug.api.listings.images.dto.AttachListingImageRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping({"/api/v1/listings", "/listings"})
public class ListingController {

    private final ListingService listingService;

    public ListingController(ListingService listingService) {
        this.listingService = listingService;
    }

    @PostMapping
    public ListingResponse create(Authentication authentication, @Valid @RequestBody CreateListingRequest request) {
        return listingService.create(authentication.getName(), request);
    }

    @GetMapping("/my")
    public MyListingsResponse myListings(Authentication authentication, @RequestParam(name = "status", required = false) String status) {
        return listingService.myListings(authentication.getName(), status);
    }

    @PutMapping("/{id}")
    public ListingResponse update(Authentication authentication, @PathVariable("id") Long id, @Valid @RequestBody UpdateListingRequest request) {
        return listingService.update(authentication.getName(), id, request);
    }

    @PostMapping("/{id}/images")
    public ListingResponse attachImage(
            Authentication authentication,
            @PathVariable("id") Long id,
            @Valid @RequestBody AttachListingImageRequest request) {
        return listingService.attachImage(authentication.getName(), id, request);
    }

    @DeleteMapping("/{id}/images/{imageId}")
    public ListingResponse removeImage(
            Authentication authentication,
            @PathVariable("id") Long id,
            @PathVariable("imageId") Long imageId) {
        return listingService.removeImage(authentication.getName(), id, imageId);
    }

    @PostMapping("/{id}/delete")
    public ListingResponse delete(Authentication authentication, @PathVariable("id") Long id) {
        return listingService.softDelete(authentication.getName(), id);
    }

    @PostMapping("/{id}/restore")
    public ListingResponse restore(Authentication authentication, @PathVariable("id") Long id) {
        return listingService.restore(authentication.getName(), id);
    }

    @PostMapping("/{id}/sold")
    public ListingResponse markSold(Authentication authentication, @PathVariable("id") Long id) {
        return listingService.markSold(authentication.getName(), id);
    }

    @PostMapping("/{id}/purge")
    public ResponseEntity<?> purge(Authentication authentication, @PathVariable("id") Long id) {
        listingService.purge(authentication.getName(), id);
        return ResponseEntity.ok(Map.of("message", "Purged"));
    }
}
