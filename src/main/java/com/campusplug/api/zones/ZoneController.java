package com.campusplug.api.zones;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping({"/api/v1/zones", "/api/zones", "/zones"})
public class ZoneController {

    private final ZoneRepository zoneRepository;

    public ZoneController(ZoneRepository zoneRepository) {
        this.zoneRepository = zoneRepository;
    }

    /**
     * G9 — Returns all campus zones (id/name/tag/accessType).
     * Flutter uses this to populate the zone picker on the map screen.
     * Geometry is NOT returned (too verbose; Flutter renders from hardcoded KMZ coords).
     */
    @GetMapping
    public List<ZoneResponse> listZones() {
        return zoneRepository.findAllProjected().stream()
                .map(z -> new ZoneResponse(z.getId(), z.getName(), z.getTag(), z.getAccessType()))
                .toList();
    }
}
