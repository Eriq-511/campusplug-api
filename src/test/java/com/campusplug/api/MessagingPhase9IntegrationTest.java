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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CampusplugApiApplication.class)
@AutoConfigureMockMvc
@SuppressWarnings({"unused", "resource"})
class MessagingPhase9IntegrationTest {

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
    void onlyParticipantsCanSend_andSoldListingsBlockSending_andLongPollWorks() throws Exception {
        String ip = "10.9.0." + ThreadLocalRandom.current().nextInt(2, 250);

        String sellerEmail = "p9seller" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String buyerEmail = "p9buyer" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String otherEmail = "p9other" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";

        String sellerToken = registerAndLogin(ip, sellerEmail, "2050/BIT/" + ThreadLocalRandom.current().nextInt(100, 999));
        String buyerToken = registerAndLogin(ip, buyerEmail, "2051/BIT/" + ThreadLocalRandom.current().nextInt(100, 999));
        String otherToken = registerAndLogin(ip, otherEmail, "2052/BIT/" + ThreadLocalRandom.current().nextInt(100, 999));

        Long listingId = createListing(sellerToken, "Messageable Item", "ELECTRONICS", 0.3290, 32.5710);
        Long conversationId = createConversation(buyerToken, listingId);

        // Buyer can send.
        String sendBody = """
                { "body": "Hello" }
                """;

        String sendResp = mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + buyerToken)
                        .content(sendBody))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode sendJson = objectMapper.readTree(sendResp);
        assertThat(sendJson.get("conversationId").asLong()).isEqualTo(conversationId);
        assertThat(sendJson.get("body").asText()).isEqualTo("Hello");

        // Other user cannot send.
        String otherResp = mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + otherToken)
                        .content(sendBody))
                .andExpect(status().isForbidden())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(otherResp).get("code").asText()).isEqualTo("NOT_PARTICIPANT");

        // Long-poll returns empty when nothing new.
        String lpResp = mockMvc.perform(get("/api/v1/conversations/{id}/messages/long-poll", conversationId)
                        .param("afterMessageId", "999999")
                        .param("timeoutSeconds", "1")
                        .header("Authorization", "Bearer " + buyerToken))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(lpResp).get("items").size()).isEqualTo(0);

        // Mark listing SOLD and confirm sending blocked.
        mockMvc.perform(post("/api/v1/listings/{id}/sold", listingId)
                        .header("Authorization", "Bearer " + sellerToken))
                .andExpect(status().isOk());

        String soldResp = mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + buyerToken)
                        .content(sendBody))
                .andExpect(status().isConflict())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(objectMapper.readTree(soldResp).get("code").asText()).isEqualTo("LISTING_SOLD");
    }

        @Test
        void longPollReturnsImmediatelyWhenNewMessagesArrive() throws Exception {
                String ip = "10.9.1." + ThreadLocalRandom.current().nextInt(2, 250);

                String sellerEmail = "p9lp_seller" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
                String buyerEmail = "p9lp_buyer" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";

                String sellerToken = registerAndLogin(ip, sellerEmail, "2060/BIT/" + ThreadLocalRandom.current().nextInt(100, 999));
                String buyerToken = registerAndLogin(ip, buyerEmail, "2061/BIT/" + ThreadLocalRandom.current().nextInt(100, 999));

                Long listingId = createListing(sellerToken, "LP Item", "ELECTRONICS", 0.3290, 32.5710);
                Long conversationId = createConversation(buyerToken, listingId);

                String firstSend = mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header("Authorization", "Bearer " + buyerToken)
                                .content("{\"body\":\"First\"}"))
                                .andExpect(status().isOk())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                long afterId = objectMapper.readTree(firstSend).get("id").asLong();

                var exec = Executors.newSingleThreadExecutor();
                try {
                        CompletableFuture<String> longPollFuture = CompletableFuture.supplyAsync(() -> {
                                try {
                                        return mockMvc.perform(get("/api/v1/conversations/{id}/messages/long-poll", conversationId)
                                                                        .param("afterMessageId", String.valueOf(afterId))
                                                                        .param("timeoutSeconds", "10")
                                                                        .header("Authorization", "Bearer " + buyerToken))
                                                        .andExpect(status().isOk())
                                                        .andReturn()
                                                        .getResponse()
                                                        .getContentAsString();
                                } catch (Exception e) {
                                        throw new RuntimeException(e);
                                }
                        }, exec);

                        // Give the long-poll a moment to begin waiting.
                        Thread.sleep(250);

                        mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                                                        .contentType(MediaType.APPLICATION_JSON)
                                                        .header("Authorization", "Bearer " + buyerToken)
                                            .content("{\"body\":\"Second\"}"))
                                        .andExpect(status().isOk());

                        long start = System.currentTimeMillis();
                        String longPollResp = longPollFuture.get(3, TimeUnit.SECONDS);
                        long elapsedMs = System.currentTimeMillis() - start;
                        assertThat(elapsedMs).isLessThan(2500);

                        JsonNode json = objectMapper.readTree(longPollResp);
                        assertThat(json.get("items").size()).isGreaterThanOrEqualTo(1);
                        assertThat(json.get("items").get(0).get("body").asText()).isEqualTo("Second");
                } finally {
                        exec.shutdownNow();
                }
        }

        @Test
        void sendMessageRejectsBodyLongerThan2000Chars() throws Exception {
                String ip = "10.9.2." + ThreadLocalRandom.current().nextInt(2, 250);

                String sellerEmail = "p9len_seller" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
                String buyerEmail = "p9len_buyer" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";

                String sellerToken = registerAndLogin(ip, sellerEmail, "2070/BIT/" + ThreadLocalRandom.current().nextInt(100, 999));
                String buyerToken = registerAndLogin(ip, buyerEmail, "2071/BIT/" + ThreadLocalRandom.current().nextInt(100, 999));

                Long listingId = createListing(sellerToken, "Len Item", "ELECTRONICS", 0.3290, 32.5710);
                Long conversationId = createConversation(buyerToken, listingId);

                String tooLong = "x".repeat(2001);
                String body = objectMapper.createObjectNode().put("body", tooLong).toString();

                String resp = mockMvc.perform(post("/api/v1/conversations/{id}/messages", conversationId)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .header("Authorization", "Bearer " + buyerToken)
                                                .content(body))
                                .andExpect(status().isBadRequest())
                                .andReturn()
                                .getResponse()
                                .getContentAsString();

                JsonNode json = objectMapper.readTree(resp);
                assertThat(json.get("code").asText()).isEqualTo("VALIDATION_ERROR");
                assertThat(json.get("fieldErrors").has("body")).isTrue();
        }

    private Long createConversation(String token, Long listingId) throws Exception {
        String body = """
                { "listingId": %d }
                """.formatted(listingId);

        String resp = mockMvc.perform(post("/api/v1/conversations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        return objectMapper.readTree(resp).get("id").asLong();
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
