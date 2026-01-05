package com.campusplug.api;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class RootController {

    @GetMapping("/")
    public Map<String, Object> root() {
        return Map.of(
                "service", "campusplug-api",
                "status", "ok",
                "timestamp", Instant.now().toString(),
                "health", "/actuator/health"
        );
    }
}
