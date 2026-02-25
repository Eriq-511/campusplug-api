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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@SuppressWarnings({"unused", "resource"})
class ImagesPhase6IntegrationTest {

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

        registry.add("app.cloudinary.cloud-name", () -> "demo");
        registry.add("app.cloudinary.api-key", () -> "123456");
        registry.add("app.cloudinary.api-secret", () -> "very-secret");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void ownerOnlyAndMax10EnforcedForListingImages() throws Exception {
        String ip1 = "10.2.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email1 = "p6owner" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo1 = "2028/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token1 = registerAndLogin(ip1, email1, regNo1);

        String ip2 = "10.2.1." + ThreadLocalRandom.current().nextInt(2, 250);
        String email2 = "p6other" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo2 = "2029/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token2 = registerAndLogin(ip2, email2, regNo2);

        Long listingId = createListing(token1);

        // Non-owner cannot get upload signature for someone else's listing.
        String signatureReq = """
                {
                  "listingId": %d,
                  "folder": "campusplug/listings/%d",
                  "publicId": "campusplug/listings/%d/1"
                }
                """.formatted(listingId, listingId, listingId);

        String signatureForbidden = mockMvc.perform(post("/api/v1/uploads/cloudinary/signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token2)
                        .content(signatureReq))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(signatureForbidden).get("code").asText()).isEqualTo("NOT_OWNER");

        String signatureOk = mockMvc.perform(post("/api/v1/uploads/cloudinary/signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token1)
                        .content(signatureReq))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode signatureJson = objectMapper.readTree(signatureOk);
        assertThat(signatureJson.get("signature").asText()).isNotBlank();
        assertThat(signatureJson.get("apiKey").asText()).isEqualTo("123456");

        // Non-owner cannot attach images.
        String attachBody = """
                {
                  "publicId": "campusplug/listings/%d/1",
                  "secureUrl": "https://res.cloudinary.com/demo/image/upload/v1/test.jpg",
                  "width": 100,
                  "height": 100,
                  "bytes": 1234,
                  "format": "jpg"
                }
                """.formatted(listingId);

        String attachForbidden = mockMvc.perform(post("/api/v1/listings/" + listingId + "/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token2)
                        .content(attachBody))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(attachForbidden).get("code").asText()).isEqualTo("NOT_OWNER");

        // Owner can attach up to 10.
        Long imageId = null;
        for (int i = 1; i <= 10; i++) {
            String body = attachBody
                    .replace("/1\"", "/" + i + "\"")
                    .replace("test.jpg", "test" + i + ".jpg");

            String attached = mockMvc.perform(post("/api/v1/listings/" + listingId + "/images")
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Authorization", "Bearer " + token1)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();

            JsonNode listingJson = objectMapper.readTree(attached);
            assertThat(listingJson.get("images").size()).isEqualTo(i);
            if (i == 1) {
                imageId = listingJson.get("images").get(0).get("id").asLong();
            }
        }

        // 11th is rejected.
        String attach11 = attachBody
                .replace("/1\"", "/11\"")
                .replace("test.jpg", "test11.jpg");

        String limitExceeded = mockMvc.perform(post("/api/v1/listings/" + listingId + "/images")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token1)
                        .content(attach11))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(limitExceeded).get("code").asText()).isEqualTo("IMAGE_LIMIT_EXCEEDED");

        // Non-owner cannot remove.
        String deleteForbidden = mockMvc.perform(delete("/api/v1/listings/" + listingId + "/images/" + imageId)
                        .header("Authorization", "Bearer " + token2))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(deleteForbidden).get("code").asText()).isEqualTo("NOT_OWNER");

        // Owner can remove.
        String deleted = mockMvc.perform(delete("/api/v1/listings/" + listingId + "/images/" + imageId)
                        .header("Authorization", "Bearer " + token1))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(deleted).get("images").size()).isEqualTo(9);
    }

    private Long createListing(String token) throws Exception {
        String createListing = """
                {
                  "title": "Phone",
                  "priceUgx": 150000,
                  "categoryCode": "ELECTRONICS",
                  "description": "Good",
                  "useRegisteredLocation": false,
                  "locationText": "Wandegeya",
                  "campus": "main"
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

        JsonNode json = objectMapper.readTree(loginResponse);
        return json.get("token").asText();
    }
}
