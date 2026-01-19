package com.campusplug.api.bookmarks;

import com.campusplug.api.bookmarks.dto.BookmarkCardResponse;
import com.campusplug.api.bookmarks.dto.BookmarkPageResponse;
import com.campusplug.api.bookmarks.dto.CreateBookmarkRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/bookmarks", "/bookmarks"})
public class BookmarkController {

    private final BookmarkService bookmarkService;

    public BookmarkController(BookmarkService bookmarkService) {
        this.bookmarkService = bookmarkService;
    }

    @PostMapping
    public BookmarkCardResponse add(Authentication authentication, @Valid @RequestBody CreateBookmarkRequest request) {
        return bookmarkService.add(authentication.getName(), request.listingId());
    }

    @GetMapping
    public BookmarkPageResponse list(
            Authentication authentication,
            @RequestParam(name = "page", required = false, defaultValue = "0") int page,
            @RequestParam(name = "size", required = false, defaultValue = "20") int size) {
        return bookmarkService.list(authentication.getName(), page, size);
    }

    @DeleteMapping
    public ResponseEntity<?> remove(Authentication authentication, @RequestParam(name = "listingId") Long listingId) {
        bookmarkService.remove(authentication.getName(), listingId);
        return ResponseEntity.noContent().build();
    }
}
