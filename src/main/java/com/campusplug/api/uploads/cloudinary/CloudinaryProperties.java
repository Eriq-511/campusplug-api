package com.campusplug.api.uploads.cloudinary;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "app.cloudinary")
public class CloudinaryProperties {

    /** Cloudinary cloud name, e.g. "demo". */
    private String cloudName;

    /** Cloudinary API key used by the client for direct uploads. */
    private String apiKey;

    /** Cloudinary API secret used server-side to sign upload parameters. */
    private String apiSecret;

    public String getCloudName() {
        return cloudName;
    }

    public void setCloudName(String cloudName) {
        this.cloudName = cloudName;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiSecret() {
        return apiSecret;
    }

    public void setApiSecret(String apiSecret) {
        this.apiSecret = apiSecret;
    }
}
