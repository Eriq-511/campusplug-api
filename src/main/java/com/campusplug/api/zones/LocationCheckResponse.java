package com.campusplug.api.zones;

public class LocationCheckResponse {
    private String zoneName;
    private String zoneTag;
    private String accessType;   // "full" | "buffer" | "restricted"
    private long listingCount;
    private String previousZoneTag;

    public LocationCheckResponse() {}

    public LocationCheckResponse(String zoneName, String zoneTag, String accessType,
                                  long listingCount, String previousZoneTag) {
        this.zoneName = zoneName;
        this.zoneTag = zoneTag;
        this.accessType = accessType;
        this.listingCount = listingCount;
        this.previousZoneTag = previousZoneTag;
    }

    public String getZoneName() { return zoneName; }
    public void setZoneName(String zoneName) { this.zoneName = zoneName; }

    public String getZoneTag() { return zoneTag; }
    public void setZoneTag(String zoneTag) { this.zoneTag = zoneTag; }

    public String getAccessType() { return accessType; }
    public void setAccessType(String accessType) { this.accessType = accessType; }

    public long getListingCount() { return listingCount; }
    public void setListingCount(long listingCount) { this.listingCount = listingCount; }

    public String getPreviousZoneTag() { return previousZoneTag; }
    public void setPreviousZoneTag(String previousZoneTag) { this.previousZoneTag = previousZoneTag; }
}
