package com.campusplug.api.uploads.cloudinary.dto;

import jakarta.validation.constraints.NotNull;

public class CloudinarySignatureRequest {

    public enum UploadContext { LISTING, AVATAR }

    /**
     * Determines what is being uploaded.
     * Defaults to LISTING for backwards compatibility.
     */
    @NotNull
    private UploadContext uploadContext = UploadContext.LISTING;

    /** Required when uploadContext == LISTING; ignored for AVATAR. */
    private Long listingId;

    /** Optional folder for Cloudinary upload (e.g. "campusplug/listings/123"). */
    private String folder;

    /** Optional public_id for deterministic naming. */
    private String publicId;

    /** Optional overwrite flag. Defaults to false. */
    private Boolean overwrite;

    /** Optional unix timestamp seconds; if null server generates. */
    private Long timestamp;

    public Long getListingId() {
        return listingId;
    }

    public void setListingId(Long listingId) {
        this.listingId = listingId;
    }

    public UploadContext getUploadContext() {
        return uploadContext;
    }

    public void setUploadContext(UploadContext uploadContext) {
        this.uploadContext = uploadContext;
    }

    public String getFolder() {
        return folder;
    }

    public void setFolder(String folder) {
        this.folder = folder;
    }

    public String getPublicId() {
        return publicId;
    }

    public void setPublicId(String publicId) {
        this.publicId = publicId;
    }

    public Boolean getOverwrite() {
        return overwrite;
    }

    public void setOverwrite(Boolean overwrite) {
        this.overwrite = overwrite;
    }

    public Long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Long timestamp) {
        this.timestamp = timestamp;
    }
}
