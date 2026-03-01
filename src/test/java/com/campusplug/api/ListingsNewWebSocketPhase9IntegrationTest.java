package com.campusplug.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CampusplugApiApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@SuppressWarnings({"unused", "resource"})
class ListingsNewWebSocketPhase9IntegrationTest {

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

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void newActiveListingPublishesExactlyOneListingsNewEvent() throws Exception {
        String ip = "10.9.3." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "p9ws" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2080/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);

        String token = registerAndLogin(ip, email, regNo);

        BlockingQueue<JsonNode> events = new LinkedBlockingQueue<>();

        WebSocketStompClient stompClient = new WebSocketStompClient(new StandardWebSocketClient());
        MappingJackson2MessageConverter converter = new MappingJackson2MessageConverter();
        converter.setObjectMapper(objectMapper);
        stompClient.setMessageConverter(converter);

        String url = "ws://localhost:" + port + "/ws";

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + token);

        StompSession session = stompClient
                .connectAsync(url, new WebSocketHttpHeaders(), connectHeaders, new StompSessionHandlerAdapter() {
                })
                .get(10, TimeUnit.SECONDS);

        session.subscribe("/topic/listings.new", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return JsonNode.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                events.add((JsonNode) payload);
            }
        });

        // Create listing after subscription.
        createListing(ip, token, "WS Item", "ELECTRONICS", 0.3290, 32.5710);

        JsonNode first = events.poll(5, TimeUnit.SECONDS);
        assertThat(first).isNotNull();
        assertThat(first.get("title").asText()).isEqualTo("WS Item");

        // Ensure no duplicates shortly after.
        JsonNode second = events.poll(1, TimeUnit.SECONDS);
        assertThat(second).isNull();

        session.disconnect();
    }

    private void createListing(String ip, String token, String title, String category, double lat, double lng) {
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("Authorization", "Bearer " + token);
        headers.add("X-Forwarded-For", ip);

        ResponseEntity<String> resp = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/listings",
                HttpMethod.POST,
                new HttpEntity<>(createListing, headers),
                String.class
        );

        assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
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

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.add("X-Forwarded-For", ip);

        ResponseEntity<String> reg = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/auth/register",
                HttpMethod.POST,
                new HttpEntity<>(registerBody, headers),
                String.class
        );
        assertThat(reg.getStatusCode().is2xxSuccessful()).isTrue();

        String loginBody = """
                {
                  "email": "%s",
                  "password": "password123"
                }
                """.formatted(email);

        ResponseEntity<String> login = restTemplate.exchange(
                "http://localhost:" + port + "/api/v1/auth/login",
                HttpMethod.POST,
                new HttpEntity<>(loginBody, headers),
                String.class
        );
        assertThat(login.getStatusCode().is2xxSuccessful()).isTrue();

        return objectMapper.readTree(login.getBody()).get("token").asText();
    }
}
