package com.campusplug.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
@SuppressWarnings({"unused", "resource"})
class AuthPhase3IntegrationTest {

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

        @Autowired
        RedisConnectionFactory redisConnectionFactory;

        @BeforeEach
        void flushRedis() {
                // Rate limiting, JWT revocation, presence, etc. use Redis.
                // Flush between tests to avoid cross-test interference and flakiness.
                try (var connection = redisConnectionFactory.getConnection()) {
                        connection.serverCommands().flushDb();
                }
        }

    @Test
    void rootAndHealthArePublic() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());
    }

    @Test
    void registerRejectsPasswordMismatch() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "mismatch" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";

        String body = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "2023/BIT/216",
                  "email": "%s",
                  "password": "password123",
                  "confirmPassword": "password1234"
                }
                """.formatted(email);

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("PASSWORD_MISMATCH");
    }

    @Test
    void registerRejectsWeakPasswordByValidation() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "weak" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";

        String body = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "2023/BIT/216",
                  "email": "%s",
                  "password": "short",
                  "confirmPassword": "short"
                }
                """.formatted(email);

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("VALIDATION_ERROR");
        assertThat(json.get("fieldErrors").has("password")).isTrue();
    }

    @Test
    void registerRejectsInvalidPhoneNumberWhenProvided() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "badphone" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";

        String body = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "2023/BIT/216",
                  "email": "%s",
                  "phoneNumber": "0700",
                  "password": "password123",
                  "confirmPassword": "password123"
                }
                """.formatted(email);

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("INVALID_PHONE_NUMBER");
    }

    @Test
    void registerRejectsInvalidRegistrationNumberFormat() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "badreg" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";

        String body = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "not-a-regno",
                  "email": "%s",
                  "password": "password123",
                  "confirmPassword": "password123"
                }
                """.formatted(email);

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("INVALID_REGISTRATION_NUMBER");
    }

    @Test
    void registerRejectsDuplicateEmail() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        int n = ThreadLocalRandom.current().nextInt(100, 999);
        String email = "dupemail" + n + "@must.ac.ug";

        String body1 = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "2024/BIT/%d",
                  "email": "%s",
                                                                        "registeredLocation": {
                                                                                "label": "MUST Main Campus",
                                                                                "lat": -0.6089,
                                                                                "lng": 30.6570
                                                                        },
                  "password": "password123",
                  "confirmPassword": "password123"
                }
                """.formatted(n, email);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body1))
                .andExpect(status().isOk());

        String body2 = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "2024/BIT/%d",
                  "email": "%s",
                                                                        "registeredLocation": {
                                                                                "label": "MUST Main Campus",
                                                                                "lat": -0.6089,
                                                                                "lng": 30.6570
                                                                        },
                  "password": "password123",
                  "confirmPassword": "password123"
                }
                """.formatted(n + 1, email);

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body2))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("DUPLICATE_EMAIL");
    }

    @Test
    void registerRejectsDuplicateRegistrationNumber() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        int n = ThreadLocalRandom.current().nextInt(100, 999);
        String regNo = "2025/BIT/" + n;
        String email1 = "dupreg" + n + "@must.ac.ug";
        String email2 = "dupreg" + (n + 1) + "@must.ac.ug";

        String body1 = """
                {
                  "fullName": "Test User",
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
                """.formatted(regNo, email1);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body1))
                .andExpect(status().isOk());

        String body2 = """
                {
                  "fullName": "Test User",
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
                """.formatted(regNo, email2);

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body2))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("DUPLICATE_REGISTRATION_NUMBER");
    }

    @Test
    void rateLimitTriggers429ForLogin() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "ratelimit" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2026/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String regBody = """
                {
                  "fullName": "Rate Limit User",
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
                        .content(regBody))
                .andExpect(status().isOk());

        String loginBody = """
                {
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(email);

        for (int i = 0; i < 5; i++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .with(req -> {
                                req.setRemoteAddr(ip);
                                return req;
                            })
                            .content(loginBody))
                    .andExpect(status().isOk());
        }

        String response = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(loginBody))
                .andExpect(status().is(429))
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("RATE_LIMITED");
    }

    @Test
    void registerRejectsNonAllowedDomain() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "test" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@gmail.com";

        String body = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "2023/BIT/216",
                  "email": "%s",
                  "phoneNumber": "+256700000000",
                  "password": "password123",
                  "confirmPassword": "password123"
                }
                """.formatted(email);

        String response = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(body))
                .andExpect(status().isBadRequest())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("code").asText()).isEqualTo("EMAIL_DOMAIN_NOT_ALLOWED");
    }

    @Test
    void registerThenLoginWorks() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        int n = ThreadLocalRandom.current().nextInt(100, 999);
        String rawRegNo = String.format("2023BIT%03dPS", n);
        String expectedRegNo = String.format("2023/BIT/%03d/PS", n);
        String email = "test" + n + "@must.ac.ug";

        String regBody = """
                {
                  "fullName": "Test User",
                  "registrationNumber": "%s",
                  "email": "%s",
                  "phoneNumber": "+256700000000",
                                                                        "registeredLocation": {
                                                                                "label": "MUST Main Campus",
                                                                                "lat": -0.6089,
                                                                                "lng": 30.6570
                                                                        },
                  "password": "password123",
                  "confirmPassword": "password123"
                }
                """.formatted(rawRegNo, email);

        String regResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(regBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode regJson = objectMapper.readTree(regResponse);
        assertThat(regJson.get("token").asText()).isNotBlank();
        assertThat(regJson.get("user").get("email").asText()).isEqualTo(email);
        assertThat(regJson.get("user").get("registrationNumber").asText()).isEqualTo(expectedRegNo);

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

        JsonNode loginJson = objectMapper.readTree(loginResponse);
        assertThat(loginJson.get("token").asText()).isNotBlank();
                assertThat(loginJson.get("user").get("email").asText()).isEqualTo(email);
    }

    @Test
    void forgotAndResetPasswordFlowWorksInNonProd() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "reset" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2024/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String regBody = """
                {
                  "fullName": "Reset User",
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
                        .content(regBody))
                .andExpect(status().isOk());

        String forgotBody = """
                { "email": "%s" }
                """.formatted(email);

        String forgotResponse = mockMvc.perform(post("/api/v1/auth/forgot-password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(forgotBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode forgotJson = objectMapper.readTree(forgotResponse);
        String token = forgotJson.get("resetToken").asText();
        assertThat(token).isNotBlank();

        String resetBody = """
                {
                  "token": "%s",
                  "password": "newpassword123",
                  "confirmPassword": "newpassword123"
                }
                """.formatted(token);

        mockMvc.perform(post("/api/v1/auth/reset-password")
                        .contentType(MediaType.APPLICATION_JSON)
                                                .with(req -> {
                                                        req.setRemoteAddr(ip);
                                                        return req;
                                                })
                        .content(resetBody))
                .andExpect(status().isOk());

        String loginBody = """
                {
                  "email": "%s",
                  "password": "newpassword123"
                }
                """.formatted(email);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(loginBody))
                .andExpect(status().isOk());
    }

    @Test
    void logoutRevokesJwtForProtectedEndpoints() throws Exception {
        String ip = "10.0.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "logout" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2025/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String regBody = """
                {
                  "fullName": "Logout User",
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

        String regResponse = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> {
                            req.setRemoteAddr(ip);
                            return req;
                        })
                        .content(regBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String token = objectMapper.readTree(regResponse).get("token").asText();
        assertThat(token).isNotBlank();

                mockMvc.perform(get("/actuator/info")
                                                .with(req -> {
                                                        req.setRemoteAddr(ip);
                                                        return req;
                                                }))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/info")
                                                .header("Authorization", "Bearer " + token)
                                                .with(req -> {
                                                        req.setRemoteAddr(ip);
                                                        return req;
                                                }))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout")
                                                .header("Authorization", "Bearer " + token)
                                                .with(req -> {
                                                        req.setRemoteAddr(ip);
                                                        return req;
                                                }))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info")
                                                .header("Authorization", "Bearer " + token)
                                                .with(req -> {
                                                        req.setRemoteAddr(ip);
                                                        return req;
                                                }))
                .andExpect(status().isUnauthorized());
    }
}
