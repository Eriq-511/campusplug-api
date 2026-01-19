package com.campusplug.api.uploads.cloudinary.dto;

import java.util.Map;

public record CloudinarySignatureResponse(
        String cloudName,
        String apiKey,
        long timestamp,
        String signature,
        Map<String, String> params
) {
}
