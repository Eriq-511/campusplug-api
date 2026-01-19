package com.campusplug.api.uploads.cloudinary;

import com.campusplug.api.uploads.cloudinary.dto.CloudinarySignatureRequest;
import com.campusplug.api.uploads.cloudinary.dto.CloudinarySignatureResponse;
import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping({"/api/v1/uploads", "/uploads"})
public class CloudinarySignatureController {

    private final CloudinarySignatureService signatureService;

    public CloudinarySignatureController(CloudinarySignatureService signatureService) {
        this.signatureService = signatureService;
    }

    @PostMapping("/cloudinary/signature")
    public CloudinarySignatureResponse signature(Authentication authentication, @Valid @RequestBody CloudinarySignatureRequest request) {
        return signatureService.createSignature(authentication.getName(), request);
    }
}
