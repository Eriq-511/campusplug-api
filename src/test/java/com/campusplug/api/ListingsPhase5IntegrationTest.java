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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@SuppressWarnings({"unused", "resource"})
class ListingsPhase5IntegrationTest {

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
    void createListingUsesRegisteredLocationWhenToggleEnabled() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "p5loc" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);

        String setProfile = """
                {
                  "campus": "main",
                  "registeredLocation": {
                    "label": "Library",
                    "lat": 0.329,
                    "lng": 32.571
                  }
                }
                """;

        mockMvc.perform(put("/api/v1/users/profile")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(setProfile))
                .andExpect(status().isOk());

        String createListing = """
                {
                  "title": "Laptop",
                  "priceUgx": 1000000,
                  "categoryCode": "ELECTRONICS",
                  "description": "Good condition",
                  "useRegisteredLocation": true
                }
                """;

        String created = mockMvc.perform(post("/api/v1/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(createListing))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode createdJson = objectMapper.readTree(created);
        assertThat(createdJson.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(createdJson.get("locationText").asText()).isEqualTo("Library");
        assertThat(createdJson.get("campus").asText()).isEqualTo("main");

        String my = mockMvc.perform(get("/api/v1/listings/my")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode myJson = objectMapper.readTree(my);
        assertThat(myJson.get("items").size()).isGreaterThan(0);
    }

    @Test
    void statusTransitionsAndOwnerOnlyEnforced() throws Exception {
        String ip1 = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email1 = "p5owner" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo1 = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token1 = registerAndLogin(ip1, email1, regNo1);

        String ip2 = "10.0.1." + ThreadLocalRandom.current().nextInt(2, 250);
        String email2 = "p5other" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo2 = "2027/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token2 = registerAndLogin(ip2, email2, regNo2);

        String createListing = """
                {
                  "title": "Chair",
                  "priceUgx": 50000,
                  "categoryCode": "HOME",
                  "description": "Wooden",
                  "useRegisteredLocation": false,
                  "locationText": "Wandegeya"
                }
                """;

        String created = mockMvc.perform(post("/api/v1/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token1)
                        .content(createListing))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long id = objectMapper.readTree(created).get("id").asLong();

        String notOwnerResponse = mockMvc.perform(post("/api/v1/listings/" + id + "/delete")
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(notOwnerResponse).get("code").asText()).isEqualTo("NOT_OWNER");

        String deleted = mockMvc.perform(post("/api/v1/listings/" + id + "/delete")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(deleted).get("status").asText()).isEqualTo("DELETED");

        String soldInvalid = mockMvc.perform(post("/api/v1/listings/" + id + "/sold")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(soldInvalid).get("code").asText()).isEqualTo("INVALID_STATE");

        mockMvc.perform(post("/api/v1/listings/" + id + "/restore")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk());

        String sold = mockMvc.perform(post("/api/v1/listings/" + id + "/sold")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(sold).get("status").asText()).isEqualTo("SOLD");

        String purgeInvalid = mockMvc.perform(post("/api/v1/listings/" + id + "/purge")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(purgeInvalid).get("code").asText()).isEqualTo("INVALID_STATE");

        String deleteSoldInvalid = mockMvc.perform(post("/api/v1/listings/" + id + "/delete")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(objectMapper.readTree(deleteSoldInvalid).get("code").asText()).isEqualTo("INVALID_STATE");

        // Create a fresh listing to validate delete->purge flow (purge is DELETED-only).
        String create2 = """
                {
                  "title": "Table",
                  "priceUgx": 60000,
                  "categoryCode": "HOME",
                  "description": "Simple",
                  "useRegisteredLocation": false,
                  "locationText": "Wandegeya"
                }
                """;

        String created2 = mockMvc.perform(post("/api/v1/listings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token1)
                        .content(create2))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        Long id2 = objectMapper.readTree(created2).get("id").asLong();

        mockMvc.perform(post("/api/v1/listings/" + id2 + "/delete")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/listings/" + id2 + "/purge")
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk());
    }

    private String registerAndLogin(String ip, String email, String regNo) throws Exception {
        String registerBody = """
                {
                  "fullName": "User",
                  "registrationNumber": "%s",
                  "email": "%s",
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

        JsonNode json = objectMapper.readTree(loginResponse);
        return json.get("token").asText();
    }
}
