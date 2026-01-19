package com.campusplug.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@SuppressWarnings({"unused", "resource"})
class PostgisSmokeTest {

    private static final DockerImageName POSTGIS_IMAGE = DockerImageName
            .parse("postgis/postgis:16-3.4")
            .asCompatibleSubstituteFor("postgres");

    @Container
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>(POSTGIS_IMAGE)
            .withDatabaseName("campusplug")
            .withUsername("campusplug")
            .withPassword("campusplug")
            .withStartupTimeout(Duration.ofMinutes(3));

    @DynamicPropertySource
    static void dynamicProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("management.health.redis.enabled", () -> "false");
    }

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void postgisExtensionIsUsable() {
        String version = jdbcTemplate.queryForObject("select PostGIS_Full_Version()", String.class);
        assertThat(version).isNotBlank();
    }

    @Test
    void expectedCoreTablesExist() {
        Integer users = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name='users'",
                Integer.class);
        Integer listings = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name='listings'",
                Integer.class);
        Integer messages = jdbcTemplate.queryForObject(
                "select count(*) from information_schema.tables where table_schema='public' and table_name='messages'",
                Integer.class);

        assertThat(users).isEqualTo(1);
        assertThat(listings).isEqualTo(1);
        assertThat(messages).isEqualTo(1);
    }
}
