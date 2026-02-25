package com.campusplug.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CampusplugApiApplication.class)
@AutoConfigureMockMvc
@SuppressWarnings({"unused", "resource"})
class BookmarksPhase8IntegrationTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

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

        registry.add("management.health.redis.enabled", () -> "false");
        registry.add("app.auth.allowed-email-domains", () -> "must.ac.ug,std.must.ac.ug");
        registry.add("app.auth.jwt.secret", () -> "test-secret-please-change");
        registry.add("app.auth.jwt.ttl-seconds", () -> "3600");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void removeBookmarkIsIdempotentAndSoldListingStillAppears() throws Exception {
        String ip = "10.8.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "p8bm" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2040/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token = registerAndLogin(ip, email, regNo);

        Long listingId = createListing(token, "Bookmarkable Item", "ELECTRONICS", 0.3290, 32.5710);

        // Mark sold.
        mockMvc.perform(post("/api/v1/listings/{id}/sold", listingId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Add bookmark.
        String addBody = """
                { "listingId": %d }
                """.formatted(listingId);

        mockMvc.perform(post("/api/v1/bookmarks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(addBody))
                .andExpect(status().isOk());

        // List bookmarks: must include SOLD status.
        String listResponse = mockMvc.perform(get("/api/v1/bookmarks")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode listJson = objectMapper.readTree(listResponse);
        assertThat(listJson.get("items").size()).isGreaterThanOrEqualTo(1);

        boolean foundSold = false;
        for (JsonNode item : listJson.get("items")) {
            if (item.get("id").asLong() == listingId) {
                assertThat(item.get("status").asText()).isEqualTo("SOLD");
                foundSold = true;
            }
        }
        assertThat(foundSold).isTrue();

        // Remove bookmark twice (idempotent).
        mockMvc.perform(delete("/api/v1/bookmarks")
                        .param("listingId", String.valueOf(listingId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(delete("/api/v1/bookmarks")
                        .param("listingId", String.valueOf(listingId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
    }

    private Long createListing(String token, String title, String category, double lat, double lng) throws Exception {
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

        String created = mockMvc.perform(post("/api/v1/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(createListing))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(created).get("id").asLong();
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

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
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

        return objectMapper.readTree(loginResponse).get("token").asText();
    }
}
