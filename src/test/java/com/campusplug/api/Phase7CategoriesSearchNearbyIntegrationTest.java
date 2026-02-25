package com.campusplug.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CampusplugApiApplication.class)
@AutoConfigureMockMvc
@SuppressWarnings({"unused", "resource"})
class Phase7CategoriesSearchNearbyIntegrationTest {

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

        // Make TTL tests fast.
        registry.add("app.cache.categories-ttl-seconds", () -> "1");
        registry.add("app.cache.search-ttl-seconds", () -> "1");
        registry.add("app.cache.nearby-ttl-seconds", () -> "1");
    }

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ObjectMapper objectMapper;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void categoriesCountsReflectActiveAndCacheExpiresByTtl() throws Exception {
        String ip = "10.3.0." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "p7cat" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2030/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token = registerAndLogin(ip, email, regNo);

        // Create one ACTIVE listing via API.
        createListing(token, "Gaming Laptop", "ELECTRONICS", 0.329, 32.571);

        long c1 = getCategoryCount(token, "ELECTRONICS");
        assertThat(c1).isGreaterThanOrEqualTo(1);

        // Insert another ACTIVE listing directly in DB to avoid cache eviction hooks.
        Long userId = jdbcTemplate.queryForObject(
                "select id from users where lower(email) = lower(?)",
                Long.class,
                email
        );
        assertThat(userId).isNotNull();

        jdbcTemplate.update(
                """
                insert into listings (owner_user_id, title, price_ugx, currency, category_code, description, location_text, campus, status)
                values (?, ?, ?, 'UGX', ?, ?, ?, ?, 'ACTIVE')
                """,
                userId,
                "Phone",
                150000,
                "ELECTRONICS",
                "Good",
                "Wandegeya",
                "main"
        );

        // Cached value should still be the old count until TTL.
        long c2Immediate = getCategoryCount(token, "ELECTRONICS");
        assertThat(c2Immediate).isEqualTo(c1);

        Thread.sleep(1100);

        long c2AfterTtl = getCategoryCount(token, "ELECTRONICS");
        assertThat(c2AfterTtl).isGreaterThanOrEqualTo(c1 + 1);
    }

    @Test
    void nearbyIsSortedByDistanceAndReturnsDistanceMeters() throws Exception {
        String ip = "10.3.1." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "p7near" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2031/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token = registerAndLogin(ip, email, regNo);

        Long id1 = createListing(token, "Item A", "ELECTRONICS", 0.3290, 32.5710);
        Long id2 = createListing(token, "Item B", "ELECTRONICS", 0.3310, 32.5710);

        String response = mockMvc.perform(get("/api/v1/listings/nearby")
                        .param("lat", "0.3290")
                        .param("lng", "32.5710")
                        .param("radiusKm", "5")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("items").size()).isGreaterThanOrEqualTo(2);

        JsonNode first = json.get("items").get(0);
        JsonNode second = json.get("items").get(1);

        assertThat(first.get("distanceMeters").asDouble()).isLessThanOrEqualTo(second.get("distanceMeters").asDouble());
        assertThat(first.get("id").asLong()).isIn(id1, id2);
    }

    @Test
    void searchFindsListingsAndGinIndexExists() throws Exception {
        String ip = "10.3.2." + ThreadLocalRandom.current().nextInt(2, 250);
        String email = "p7search" + ThreadLocalRandom.current().nextInt(1000, 9999) + "@must.ac.ug";
        String regNo = "2032/BIT/" + ThreadLocalRandom.current().nextInt(100, 999);
        String token = registerAndLogin(ip, email, regNo);

        createListing(token, "Gaming Laptop", "ELECTRONICS", 0.329, 32.571);

        String response = mockMvc.perform(get("/api/v1/listings/search")
                        .param("query", "laptop")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        JsonNode json = objectMapper.readTree(response);
        assertThat(json.get("items").size()).isGreaterThanOrEqualTo(1);
        assertThat(json.get("items").get(0).get("title").asText().toLowerCase()).contains("laptop");

        Integer idxCount = jdbcTemplate.queryForObject(
                "select count(*) from pg_indexes where indexname = 'ix_listings_search_tsv_gin'",
                Integer.class
        );
        assertThat(idxCount).isEqualTo(1);

        List<Map<String, Object>> explainRows = jdbcTemplate.queryForList(
                "EXPLAIN SELECT id FROM listings WHERE status = 'ACTIVE' AND search_tsv @@ plainto_tsquery('simple', 'laptop')"
        );
        String explainText = explainRows.toString().toLowerCase();
        // Planner choice is not deterministic on tiny tables; validate we are using the FTS predicate.
        assertThat(explainText).contains("search_tsv");
        assertThat(explainText).contains("@@");
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

        JsonNode json = objectMapper.readTree(loginResponse);
        return json.get("token").asText();
    }
}
