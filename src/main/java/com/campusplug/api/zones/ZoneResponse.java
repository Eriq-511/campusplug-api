package com.campusplug.api.zones;

public class ZoneResponse {
    private Long id;
    private String name;
    private String tag;
    private String accessType;

    public ZoneResponse(Long id, String name, String tag, String accessType) {
        this.id = id;
        this.name = name;
        this.tag = tag;
        this.accessType = accessType;
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getTag() { return tag; }
    public String getAccessType() { return accessType; }
}
