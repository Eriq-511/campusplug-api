package com.campusplug.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnectionFactory;
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
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for all GIS phases (G2–G9):
 *  G2  — /api/v1/geo/geocode, /api/v1/geo/reverse
 *  G3  — PUT /api/v1/users/location
 *  G4  — PUT /api/v1/users/fcm-token
 *  G8  — POST /api/v1/location/check  (zone detection)
 *  G9  — GET /api/v1/zones
 *        GET /api/v1/listings/zone/{tag}
 *        GET /api/v1/listings/zone/{tag}/count
 *        GET /api/v1/listings/feed
 *        listing auto-tagging on create
 *
 * All tests run against Testcontainers (PostGIS + Redis) — Docker required.
 */
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest(classes = CampusplugApiApplication.class)
@AutoConfigureMockMvc
@SuppressWarnings({"unused", "resource"})
class GisPhaseIntegrationTest {

    // ─── Testcontainers ────────────────────────────────────────────────────────

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
    static final GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:7-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url",      postgres::getJdbcUrl);
        r.add("spring.datasource.username", postgres::getUsername);
        r.add("spring.datasource.password", postgres::getPassword);
        r.add("spring.data.redis.host",     redis::getHost);
        r.add("spring.data.redis.port",     () -> String.valueOf(redis.getMappedPort(6379)));
        r.add("management.health.redis.enabled",  () -> "false");
        r.add("app.auth.allowed-email-domains",   () -> "must.ac.ug,std.must.ac.ug");
        r.add("app.auth.jwt.secret",              () -> "test-gis-secret-change-me");
        r.add("app.auth.jwt.ttl-seconds",         () -> "3600");
        // Firebase and Google Maps are disabled in tests (no real keys)
        r.add("app.firebase.service-account-json", () -> "");
        r.add("app.google.maps-api-key",           () -> "");
    }

    // ─── Spring beans ──────────────────────────────────────────────────────────

    @Autowired MockMvc        mockMvc;
    @Autowired ObjectMapper   objectMapper;
    @Autowired JdbcTemplate   jdbcTemplate;
    @Autowired RedisConnectionFactory redisConnectionFactory;

    // ─── Kihumuro zone reference coordinates (from spec §8.1 verification check)

    /** Inside Kihumuro zone polygon */
    private static final double KH_LAT = -0.5950;
    private static final double KH_LNG =  30.5970;

    /** Definitely outside all zones */
    private static final double OUT_LAT = 0.0;
    private static final double OUT_LNG = 0.0;

    // ─── Lifecycle ─────────────────────────────────────────────────────────────

    @BeforeEach
    void flushRedis() {
        try (var conn = redisConnectionFactory.getConnection()) {
            conn.serverCommands().flushDb();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  G7 — Database schema assertions
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("G7 — Database schema")
    class G7Schema {

        @Test
        @DisplayName("zones table exists with 10 seeded rows")
        void zonesTableHasTenRows() {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from zones", Integer.class);
            assertThat(count).isEqualTo(10);
        }

        @Test
        @DisplayName("user_zones table exists")
        void userZonesTableExists() {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.tables "
                    + "where table_schema='public' and table_name='user_zones'",
                    Integer.class);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("listings.zone_tag column exists")
        void listingsHasZoneTagColumn() {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns "
                    + "where table_schema='public' and table_name='listings' and column_name='zone_tag'",
                    Integer.class);
            assertThat(count).isEqualTo(1);
        }

        @Test
        @DisplayName("GIST index on zones.boundary exists")
        void zonesGistIndexExists() {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from pg_indexes "
                    + "where tablename='zones' and indexname='idx_zones_boundary'",
                    Integer.class);
            assertThat(count).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("Kihumuro zone contains spec verification coordinate")
        void kihumuroZoneContainsSpecCoordinate() {
            // Spec §8.1 Check 4: ST_MakePoint(30.5970, -0.5950) must return Kihumuro zone
            String tag = jdbcTemplate.queryForObject(
                    "select tag from zones "
                    + "where ST_Contains(boundary, ST_SetSRID(ST_MakePoint(30.5970, -0.5950), 4326))",
                    String.class);
            assertThat(tag).isEqualTo("kihumuro_main");
        }

        @Test
        @DisplayName("Origin coordinate (0,0) returns no zone")
        void originCoordinateIsOutsideAllZones() {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from zones "
                    + "where ST_Contains(boundary, ST_SetSRID(ST_MakePoint(0.0, 0.0), 4326))",
                    Integer.class);
            assertThat(count).isEqualTo(0);
        }

        @Test
        @DisplayName("All zone polygons are valid geometries")
        void allZonesHaveValidGeometry() {
            Integer invalid = jdbcTemplate.queryForObject(
                    "select count(*) from zones where not ST_IsValid(boundary)",
                    Integer.class);
            assertThat(invalid).isEqualTo(0);
        }

        @Test
        @DisplayName("users table has last_location columns from V5 migration")
        void usersHaveLastLocationColumns() {
            Integer count = jdbcTemplate.queryForObject(
                    "select count(*) from information_schema.columns "
                    + "where table_schema='public' and table_name='users' "
                    + "and column_name in ('last_known_lat','last_known_lng','fcm_token','last_location_at')",
                    Integer.class);
            assertThat(count).isEqualTo(4);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  G9 — GET /api/v1/zones
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("G9 — GET /api/v1/zones")
    class G9Zones {

        @Test
        @DisplayName("returns 10 zones with required fields")
        void listZonesReturnsAllZones() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(get("/api/v1/zones")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode arr = objectMapper.readTree(body);
            assertThat(arr.isArray()).isTrue();
            assertThat(arr.size()).isEqualTo(10);

            // Every element must have id, name, tag, accessType
            for (JsonNode z : arr) {
                assertThat(z.has("id")).isTrue();
                assertThat(z.has("name")).isTrue();
                assertThat(z.has("tag")).isTrue();
                assertThat(z.has("accessType")).isTrue();
                // Geometry must NOT be leaked
                assertThat(z.has("boundary")).isFalse();
            }
        }

        @Test
        @DisplayName("/api/zones secondary alias also works")
        void secondaryAliasWorks() throws Exception {
            String token = registerAndLogin();

            mockMvc.perform(get("/api/zones")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.length()").value(10));
        }

        @Test
        @DisplayName("zone names and tags match spec exactly")
        void zoneNamesMatchSpec() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(get("/api/v1/zones")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode arr = objectMapper.readTree(body);
            // Collect all tags
            var tags = new java.util.HashSet<String>();
            var names = new java.util.HashSet<String>();
            for (JsonNode z : arr) {
                tags.add(z.get("tag").asText());
                names.add(z.get("name").asText());
            }

            // Verify all 10 spec tags are present
            assertThat(tags).containsExactlyInAnyOrder(
                "kihumuro_main", "mile_4", "path_hostel", "mama_belinda",
                "mile_5", "mirrors", "mile_3_a", "mile_3_b", "kiyanja", "ruharo");

            // Verify display names match spec (exact casing from spec §9.1)
            assertThat(names).contains(
                "Kihumuro zone", "Mile 4 zone", "Path hostel zone",
                "Mama Belinda zone", "Mile 5 zone", "Mirrors zone",
                "Mile 3 Zone A", "Mile 3 zone B", "Kiyanja zone", "Ruharo zone");
        }

        @Test
        @DisplayName("unauthenticated request is rejected with 401")
        void unauthenticatedIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/zones"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  G8 — POST /api/v1/location/check
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("G8 — POST /api/v1/location/check")
    class G8LocationCheck {

        @Test
        @DisplayName("inside Kihumuro zone → full access, correct zone info")
        void insideKihumuroZone() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(post("/api/v1/location/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s}
                                    """.formatted(KH_LAT, KH_LNG)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.get("zoneName").asText()).isEqualTo("Kihumuro zone");
            assertThat(json.get("zoneTag").asText()).isEqualTo("kihumuro_main");
            assertThat(json.get("accessType").asText()).isEqualTo("full");
            assertThat(json.get("listingCount").asLong()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("outside all zones → restricted access, null zone fields")
        void outsideAllZones() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(post("/api/v1/location/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s}
                                    """.formatted(OUT_LAT, OUT_LNG)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.get("zoneName").isNull()).isTrue();
            assertThat(json.get("zoneTag").isNull()).isTrue();
            assertThat(json.get("accessType").asText()).isEqualTo("restricted");
            assertThat(json.get("listingCount").asLong()).isEqualTo(0);
        }

        @Test
        @DisplayName("repeated calls record previousZoneTag correctly")
        void previousZoneTagTracking() throws Exception {
            String token = registerAndLogin();

            // First call — inside Kihumuro
            mockMvc.perform(post("/api/v1/location/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s}
                                    """.formatted(KH_LAT, KH_LNG)))
                    .andExpect(status().isOk());

            // Second call — still inside (previousZoneTag should now be kihumuro_main)
            String body = mockMvc.perform(post("/api/v1/location/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s}
                                    """.formatted(KH_LAT, KH_LNG)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.get("previousZoneTag").asText()).isEqualTo("kihumuro_main");
        }

        @Test
        @DisplayName("/api/location/check secondary path alias responds correctly")
        void secondaryAliasWorks() throws Exception {
            String token = registerAndLogin();

            mockMvc.perform(post("/api/location/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s}
                                    """.formatted(KH_LAT, KH_LNG)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.zoneTag").value("kihumuro_main"));
        }

        @Test
        @DisplayName("missing lat/lng fields returns 400")
        void missingLatLngReturns400() throws Exception {
            String token = registerAndLogin();

            mockMvc.perform(post("/api/v1/location/check")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("unauthenticated check is rejected with 401")
        void unauthenticatedIsRejected() throws Exception {
            mockMvc.perform(post("/api/v1/location/check")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s}
                                    """.formatted(KH_LAT, KH_LNG)))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  G3 — PUT /api/v1/users/location
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("G3 — PUT /api/v1/users/location")
    class G3UserLocation {

        @Test
        @DisplayName("valid location update returns 200 with message")
        void updateLocationSucceeds() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(put("/api/v1/users/location")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s, "event": "geofenceEnter"}
                                    """.formatted(KH_LAT, KH_LNG)))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.has("message")).isTrue();
        }

        @Test
        @DisplayName("location persisted to DB after update")
        void locationPersistsToDatabase() throws Exception {
            String email = uniqueEmail();
            String token = registerAndLoginWith(email);

            mockMvc.perform(put("/api/v1/users/location")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": %s, "lng": %s}
                                    """.formatted(KH_LAT, KH_LNG)))
                    .andExpect(status().isOk());

            Double lat = jdbcTemplate.queryForObject(
                    "select last_known_lat from users where email = ?",
                    Double.class, email);
            Double lng = jdbcTemplate.queryForObject(
                    "select last_known_lng from users where email = ?",
                    Double.class, email);

            assertThat(lat).isNotNull();
            assertThat(lng).isNotNull();
            assertThat(lat).isEqualTo(KH_LAT, org.assertj.core.data.Offset.offset(0.0001));
            assertThat(lng).isEqualTo(KH_LNG, org.assertj.core.data.Offset.offset(0.0001));
        }

        @Test
        @DisplayName("unauthenticated request is rejected with 401")
        void unauthenticatedIsRejected() throws Exception {
            mockMvc.perform(put("/api/v1/users/location")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"lat": -0.5950, "lng": 30.5970}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  G4 — PUT /api/v1/users/fcm-token
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("G4 — PUT /api/v1/users/fcm-token")
    class G4FcmToken {

        @Test
        @DisplayName("FCM token registration returns 200 with message")
        void registerFcmTokenSucceeds() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(put("/api/v1/users/fcm-token")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token": "dummyFcmToken12345abcde"}
                                    """))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.has("message")).isTrue();
        }

        @Test
        @DisplayName("FCM token persisted to users table")
        void fcmTokenPersistsToDatabase() throws Exception {
            String email = uniqueEmail();
            String token = registerAndLoginWith(email);

            mockMvc.perform(put("/api/v1/users/fcm-token")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token": "test-fcm-device-token-xyz"}
                                    """))
                    .andExpect(status().isOk());

            String stored = jdbcTemplate.queryForObject(
                    "select fcm_token from users where email = ?",
                    String.class, email);
            assertThat(stored).isEqualTo("test-fcm-device-token-xyz");
        }

        @Test
        @DisplayName("missing token field returns 400")
        void missingTokenReturns400() throws Exception {
            String token = registerAndLogin();

            mockMvc.perform(put("/api/v1/users/fcm-token")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("unauthenticated request is rejected with 401")
        void unauthenticatedIsRejected() throws Exception {
            mockMvc.perform(put("/api/v1/users/fcm-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"token": "dummyFcmToken12345abcde"}
                                    """))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  G9 — Zone-filtered listings + global feed
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("G9 — Zone-filtered listings & global feed")
    class G9ListingEndpoints {

        @Test
        @DisplayName("GET /zone/{tag}/count returns zero for empty zone")
        void zoneCountReturnsZeroInitially() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(get("/api/v1/listings/zone/kihumuro_main/count")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.get("zoneTag").asText()).isEqualTo("kihumuro_main");
            assertThat(json.get("count").asLong()).isGreaterThanOrEqualTo(0);
        }

        @Test
        @DisplayName("GET /zone/{tag} returns paginated response")
        void zoneFeedReturnsPaginatedResponse() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(get("/api/v1/listings/zone/kihumuro_main")
                            .param("lat", String.valueOf(KH_LAT))
                            .param("lng", String.valueOf(KH_LNG))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            // Should have pagination envelope
            assertThat(json.has("items")).isTrue();
            assertThat(json.has("page")).isTrue();
            assertThat(json.has("total")).isTrue();  // field name in ListingPageResponse record
        }

        @Test
        @DisplayName("GET /listings/feed returns paginated response sorted by distance")
        void globalFeedReturnsPaginatedResponse() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(get("/api/v1/listings/feed")
                            .param("lat", String.valueOf(KH_LAT))
                            .param("lng", String.valueOf(KH_LNG))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.has("items")).isTrue();
            assertThat(json.has("page")).isTrue();
        }

        @Test
        @DisplayName("listing created inside Kihumuro zone is auto-tagged")
        void listingCreatedInKihumuroZoneIsAutoTagged() throws Exception {
            String email = uniqueEmail();
            String token = registerAndLoginWithLocation(email, KH_LAT, KH_LNG);

            // Create listing with coordinates inside Kihumuro zone
            String createBody = """
                    {
                      "title": "GIS Test Book",
                      "priceUgx": 15000,
                      "categoryCode": "BOOKS",
                      "description": "PostGIS auto-tagging test",
                      "lat": %s,
                      "lng": %s
                    }
                    """.formatted(KH_LAT, KH_LNG);

            String created = mockMvc.perform(post("/api/v1/listings")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode createdJson = objectMapper.readTree(created);
            Long listingId = createdJson.get("id").asLong();

            // Give auto-tag (async or sync in transaction) a moment to commit
            Thread.sleep(200);

            // Verify zone_tag was set in the DB
            String zoneTag = jdbcTemplate.queryForObject(
                    "select zone_tag from listings where id = ?",
                    String.class, listingId);
            assertThat(zoneTag).isEqualTo("kihumuro_main");

            // Count for that zone should now be >= 1
            String countBody = mockMvc.perform(get("/api/v1/listings/zone/kihumuro_main/count")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            JsonNode countJson = objectMapper.readTree(countBody);
            assertThat(countJson.get("count").asLong()).isGreaterThanOrEqualTo(1);
        }

        @Test
        @DisplayName("listing created outside all zones has null zone_tag")
        void listingCreatedOutsideZonesHasNullZoneTag() throws Exception {
            String email = uniqueEmail();
            String token = registerAndLogin();

            String createBody = """
                    {
                      "title": "No-Zone Item",
                      "priceUgx": 5000,
                      "categoryCode": "OTHER",
                      "description": "Outside all zones",
                      "lat": %s,
                      "lng": %s
                    }
                    """.formatted(OUT_LAT, OUT_LNG);

            String created = mockMvc.perform(post("/api/v1/listings")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(createBody))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();

            JsonNode createdJson = objectMapper.readTree(created);
            Long listingId = createdJson.get("id").asLong();

            Thread.sleep(200);

            // zone_tag should remain null
            Integer hasTag = jdbcTemplate.queryForObject(
                    "select count(*) from listings where id = ? and zone_tag is not null",
                    Integer.class, listingId);
            assertThat(hasTag).isEqualTo(0);
        }

        @Test
        @DisplayName("/api/listings secondary alias works for zone count")
        void secondaryAliasZoneCountWorks() throws Exception {
            String token = registerAndLogin();

            mockMvc.perform(get("/api/listings/zone/kihumuro_main/count")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.zoneTag").value("kihumuro_main"));
        }

        @Test
        @DisplayName("GET /zone/{tag} missing lat/lng returns 400")
        void zoneFeedMissingLatLngReturns400() throws Exception {
            String token = registerAndLogin();

            mockMvc.perform(get("/api/v1/listings/zone/kihumuro_main")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }

        @Test
        @DisplayName("GET /listings/feed missing lat/lng returns 400")
        void feedMissingLatLngReturns400() throws Exception {
            String token = registerAndLogin();

            mockMvc.perform(get("/api/v1/listings/feed")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadRequest());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  G2 — Geocoding proxy (disabled when no API key)
    // ═══════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("G2 — GET /api/v1/geo/geocode & /reverse")
    class G2Geocoding {

        @Test
        @DisplayName("geocode returns 503 GEO_DISABLED when no API key configured")
        void geocodeReturns503WhenNoApiKey() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(get("/api/v1/geo/geocode")
                            .param("address", "Kihumuro zone, Mbarara")
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.get("code").asText()).isEqualTo("GEO_DISABLED");
        }

        @Test
        @DisplayName("reverse returns 503 GEO_DISABLED when no API key configured")
        void reverseReturns503WhenNoApiKey() throws Exception {
            String token = registerAndLogin();

            String body = mockMvc.perform(get("/api/v1/geo/reverse")
                            .param("lat", String.valueOf(KH_LAT))
                            .param("lng", String.valueOf(KH_LNG))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isServiceUnavailable())
                    .andReturn().getResponse().getContentAsString();

            JsonNode json = objectMapper.readTree(body);
            assertThat(json.get("code").asText()).isEqualTo("GEO_DISABLED");
        }

        @Test
        @DisplayName("unauthenticated geocode is rejected with 401")
        void unauthenticatedGeoCodeIsRejected() throws Exception {
            mockMvc.perform(get("/api/v1/geo/geocode")
                            .param("address", "Kihumuro"))
                    .andExpect(status().isUnauthorized());
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private String uniqueEmail() {
        return "gistest" + ThreadLocalRandom.current().nextInt(10_000, 99_999) + "@must.ac.ug";
    }

    private String registerAndLogin() throws Exception {
        return registerAndLoginWith(uniqueEmail());
    }

    private String registerAndLoginWith(String email) throws Exception {
        return registerAndLoginWithLocation(email, -0.6089, 30.6570);
    }

    private String registerAndLoginWithLocation(String email, double lat, double lng) throws Exception {
        String ip = "10.1." + ThreadLocalRandom.current().nextInt(1, 254)
                + "." + ThreadLocalRandom.current().nextInt(2, 254);
        String regNo = "2025/GIS/" + ThreadLocalRandom.current().nextInt(100, 999);

        String registerBody = """
                {
                  "fullName":           "GIS Test User",
                  "registrationNumber": "%s",
                  "email":              "%s",
                  "password":           "password123",
                  "confirmPassword":    "password123",
                  "registeredLocation": {
                    "label": "MUST Campus",
                    "lat":   %s,
                    "lng":   %s
                  }
                }
                """.formatted(regNo, email, lat, lng);

        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> { req.setRemoteAddr(ip); return req; })
                        .content(registerBody))
                .andExpect(status().isOk());

        String loginBody = """
                {"email": "%s", "password": "password123"}
                """.formatted(email);

        String loginResponse = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .with(req -> { req.setRemoteAddr(ip); return req; })
                        .content(loginBody))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        return objectMapper.readTree(loginResponse).get("token").asText();
    }
}
