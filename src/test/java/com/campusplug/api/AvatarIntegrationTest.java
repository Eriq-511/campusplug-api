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
class AvatarIntegrationTest {

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
    void avatarSignatureIsLockedToUserPath() throws Exception {
        String ip = "10.3.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "avataruser" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);
        Long userId = extractUserId(token);

        String signatureReq = """
                {
                  "uploadContext": "AVATAR"
                }
                """;

        String signatureResponse = mockMvc.perform(post("/api/v1/uploads/cloudinary/signature")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(signatureReq))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sigJson = objectMapper.readTree(signatureResponse);
        String expectedFolder = "campusplug/avatars/" + userId;
        String expectedPublicId = expectedFolder + "/profile";

        assertThat(sigJson.get("params").get("folder").asText()).isEqualTo(expectedFolder);
        assertThat(sigJson.get("params").get("public_id").asText()).isEqualTo(expectedPublicId);
        assertThat(sigJson.get("params").get("overwrite").asText()).isEqualTo("true");
        assertThat(sigJson.get("signature").asText()).isNotBlank();
        assertThat(sigJson.get("apiKey").asText()).isEqualTo("123456");
    }

    @Test
    void avatarConfirmationRejectedWithInvalidPublicId() throws Exception {
        String ip = "10.3.1." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "avatarfail1" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);

        String confirmBody = """
                {
                  "avatarUrl": "https://res.cloudinary.com/demo/image/upload/v1/hacker/path/image.jpg",
                  "avatarPublicId": "hacker/path/image"
                }
                """;

        String response = mockMvc.perform(put("/api/v1/users/profile/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(confirmBody))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("INVALID_AVATAR_PUBLIC_ID");
    }

    @Test
    void avatarConfirmationRejectedWithInvalidUrl() throws Exception {
        String ip = "10.3.2." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "avatarfail2" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);
        Long userId = extractUserId(token);

        String expectedPublicId = "campusplug/avatars/" + userId + "/profile";

        String confirmBody = """
                {
                  "avatarUrl": "https://attacker.com/fake/image.jpg",
                  "avatarPublicId": "%s"
                }
                """.formatted(expectedPublicId);

        String response = mockMvc.perform(put("/api/v1/users/profile/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(confirmBody))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("INVALID_AVATAR_URL");
    }

    @Test
    void avatarConfirmationSucceedsWithValidPayload() throws Exception {
        String ip = "10.3.3." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "avatarvalid" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);
        Long userId = extractUserId(token);

        String expectedFolder = "campusplug/avatars/" + userId;
        String expectedPublicId = expectedFolder + "/profile";
        String validAvatarUrl = "https://res.cloudinary.com/demo/image/upload/v1234567890/" + expectedPublicId + ".jpg";

        String confirmBody = """
                {
                  "avatarUrl": "%s",
                  "avatarPublicId": "%s"
                }
                """.formatted(validAvatarUrl, expectedPublicId);

        String confirmResponse = mockMvc.perform(put("/api/v1/users/profile/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(confirmBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(confirmResponse);
        assertThat(json.get("avatarUrl").asText()).isEqualTo(validAvatarUrl);
    }

    @Test
    void avatarAppersInAuthenticatedProfile() throws Exception {
        String ip = "10.3.4." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "avatarprofile" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);
        Long userId = extractUserId(token);

        String expectedPublicId = "campusplug/avatars/" + userId + "/profile";
        String validAvatarUrl = "https://res.cloudinary.com/demo/image/upload/v1234567890/" + expectedPublicId + ".jpg";

        String confirmBody = """
                {
                  "avatarUrl": "%s",
                  "avatarPublicId": "%s"
                }
                """.formatted(validAvatarUrl, expectedPublicId);

        mockMvc.perform(put("/api/v1/users/profile/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(confirmBody))
                .andExpect(status().isOk());

        String profileResponse = mockMvc.perform(get("/api/v1/users/profile")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode profileJson = objectMapper.readTree(profileResponse);
        assertThat(profileJson.get("avatarUrl").asText()).isEqualTo(validAvatarUrl);
    }

    @Test
    void avatarAppearsInPublicProfile() throws Exception {
        String ip = "10.3.5." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "avatarpublic" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);
        Long userId = extractUserId(token);

        String expectedPublicId = "campusplug/avatars/" + userId + "/profile";
        String validAvatarUrl = "https://res.cloudinary.com/demo/image/upload/v1234567890/" + expectedPublicId + ".jpg";

        String confirmBody = """
                {
                  "avatarUrl": "%s",
                  "avatarPublicId": "%s"
                }
                """.formatted(validAvatarUrl, expectedPublicId);

        mockMvc.perform(put("/api/v1/users/profile/avatar")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(confirmBody))
                .andExpect(status().isOk());

        String publicProfileResponse = mockMvc.perform(get("/api/v1/users/" + userId + "/public")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode publicJson = objectMapper.readTree(publicProfileResponse);
        assertThat(publicJson.get("avatarUrl").asText()).isEqualTo(validAvatarUrl);
    }

    private String registerAndLogin(String ip, String email, String regNo) throws Exception {
        String registerBody = """
                {
                  "fullName": "Avatar Test User",
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
                .header("X-Forwarded-For", ip)
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
                .header("X-Forwarded-For", ip)
                .content(loginBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(loginResponse).get("accessToken").asText();
    }

    private Long extractUserId(String token) throws Exception {
        String profileResponse = mockMvc.perform(get("/api/v1/users/profile")
                .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(profileResponse).get("id").asLong();
    }
}
