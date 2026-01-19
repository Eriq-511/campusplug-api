package com.campusplug.api.auth.service;

import com.campusplug.api.auth.config.AuthProperties;
import com.campusplug.api.common.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class EmailDomainValidator {

    private final List<String> allowedDomains;

    public EmailDomainValidator(AuthProperties authProperties) {
        this.allowedDomains = authProperties.getAllowedEmailDomains();
    }

    public void validateAllowedDomain(String email) {
        String normalized = email.trim().toLowerCase(Locale.ROOT);
        int at = normalized.lastIndexOf('@');
        if (at < 0 || at == normalized.length() - 1) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_EMAIL", "Invalid email address");
        }

        String domain = normalized.substring(at + 1);
        boolean ok = allowedDomains != null && allowedDomains.stream()
                .map(d -> d.trim().toLowerCase(Locale.ROOT))
                .anyMatch(d -> d.equals(domain));

        if (!ok) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMAIL_DOMAIN_NOT_ALLOWED",
                    "Email domain is not allowed");
        }
    }
}
