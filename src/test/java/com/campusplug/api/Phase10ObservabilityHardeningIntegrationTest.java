package com.campusplug.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CampusplugApiApplication.class)
@AutoConfigureMockMvc
@ExtendWith(OutputCaptureExtension.class)
@SuppressWarnings({"unused", "resource"})
class Phase10ObservabilityHardeningIntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

    private static final String JWT_SECRET_MARKER = "phase10-jwt-secret-marker-please-do-not-log";
    private static final String ALLOWED_ORIGIN = "http://allowed.campusplug.test";

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("campusplug")
            .withUsername("campusplug")
            .withPassword("campusplug")
            .withStartupTimeout(Duration.ofMinutes(3));

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> String.valueOf(redis.getMappedPort(6379)));

        // Keep health endpoint stable.
        registry.add("management.health.redis.enabled", () -> "false");

        registry.add("app.auth.allowed-email-domains", () -> "must.ac.ug,std.must.ac.ug");
        registry.add("app.auth.jwt.secret", () -> JWT_SECRET_MARKER);
        registry.add("app.auth.jwt.ttl-seconds", () -> "3600");

        // Ensure cache is enabled + long TTL so we can prove eviction instead of expiry.
        registry.add("app.cache.categories-ttl-seconds", () -> "600");
        registry.add("app.cache.search-ttl-seconds", () -> "600");
        registry.add("app.cache.nearby-ttl-seconds", () -> "600");

        // Restrictive CORS for both HTTP and WebSocket handshake.
        registry.add("app.cors.allowed-origins", () -> ALLOWED_ORIGIN);
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void actuatorHealthIsPublicButInfoRequiresAuth() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());

        String token = registerAndLogin(
                "10.10.0." + ThreadLocalRandom.current().nextInt(2, 250),
                "p10act" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug",
                "2040/BIT/" + ThreadLocalRandom.current().nextInt(100, 999)
        );

        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void corsAllowsConfiguredOriginAndRejectsOthers() throws Exception {
        mockMvc.perform(options("/api/v1/categories")
                        .header("Origin", ALLOWED_ORIGIN)
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().is2xxSuccessful())
                .andExpect(header().string("Access-Control-Allow-Origin", ALLOWED_ORIGIN));

        mockMvc.perform(options("/api/v1/categories")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    @Test
    void cacheEvictionOccursOnListingCreate() throws Exception {
        String token = registerAndLogin(
                "10.10.1." + ThreadLocalRandom.current().nextInt(2, 250),
                "p10cache" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug",
                "2041/BIT/" + ThreadLocalRandom.current().nextInt(100, 999)
        );

        long before = getCategoryCount(token, "ELECTRONICS");

        // Warms cache.
        long beforeAgain = getCategoryCount(token, "ELECTRONICS");
        assertThat(beforeAgain).isEqualTo(before);

        createListing(token, "Phase 10 Item", "ELECTRONICS", 0.329, 32.571);

        // If eviction works, this must reflect the new listing immediately (no TTL wait).
        long after = getCategoryCount(token, "ELECTRONICS");
        assertThat(after).isEqualTo(before + 1);
    }

    @Test
    void startupDoesNotLogJwtSecret(CapturedOutput output) {
        assertThat(output.getOut()).doesNotContain(JWT_SECRET_MARKER);
        assertThat(output.getErr()).doesNotContain(JWT_SECRET_MARKER);
    }

    private long getCategoryCount(String token, String code) throws Exception {
        String response = mockMvc.perform(get("/api/v1/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode arr = objectMapper.readTree(response);
        for (JsonNode item : arr) {
            if (item.get("code").asText().equals(code)) {
                return item.get("activeListingCount").asLong();
            }
        }
        throw new IllegalStateException("Category not found: " + code);
    }

    private void createListing(String token, String title, String category, double lat, double lng) throws Exception {
        String createListing = """
                {
                  "title": "%s",
                  "priceUgx": 150000,
                  "categoryCode": "%s",
                  "description": "Good",
                  "useRegisteredLocation": false,
                  "locationText": "Wandegeya",
                  "lat": %s,
                  "lng": %s,
                  "campus": "main"
                }
                """.formatted(title, category, lat, lng);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(createListing))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String ip, String email, String regNo) throws Exception {
        String registerBody = """
                {
                  "fullName": "User",
                  "registrationNumber": "%s",
                  "email": "%s",
                                                                        "registeredLocation": {
                                                                                "label": "MUST Main Campus",
                                                                                "lat": -0.6089,
                                                                                "lng": 30.6570
                                                                        },
                  "password": "password123",
                  "confirmPassword": "password123"
                }
                """.formatted(regNo, email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(registerBody))
                .andExpect(status().isOk());

        String loginBody = """
                {
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(email);

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        return json.get("token").asText();
    }
}
