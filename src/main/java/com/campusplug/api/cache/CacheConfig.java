package com.campusplug.api.cache;

import com.fasterxml.jackson.databind.ObjectMapper.DefaultTyping;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

@Configuration
@EnableCaching
public class CacheConfig {

    public static final String CATEGORIES_CACHE = "categories:v2";
    public static final String SEARCH_CACHE = "search:v2";
    public static final String NEARBY_CACHE = "nearby:v2";

    @Bean
    public CacheManager cacheManager(
            RedisConnectionFactory connectionFactory,
            AppCacheProperties props,
            ObjectMapper objectMapper) {
        ObjectMapper redisObjectMapper = objectMapper.copy();
        redisObjectMapper.activateDefaultTypingAsProperty(
                BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.campusplug.api")
                        .allowIfSubType("java.util")
                        .allowIfSubType("java.time")
                        .build(),
                DefaultTyping.NON_FINAL,
                "@class"
        );

        RedisSerializationContext.SerializationPair<String> keySerializer =
                RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer());
        RedisSerializationContext.SerializationPair<Object> valueSerializer =
            RedisSerializationContext.SerializationPair.fromSerializer(new GenericJackson2JsonRedisSerializer(redisObjectMapper));

        RedisCacheConfiguration base = RedisCacheConfiguration.defaultCacheConfig()
                .disableCachingNullValues()
                .serializeKeysWith(keySerializer)
                .serializeValuesWith(valueSerializer)
                .prefixCacheNameWith(props.getKeyPrefix());

        Map<String, RedisCacheConfiguration> configs = new LinkedHashMap<>();
        configs.put(CATEGORIES_CACHE, base.entryTtl(Duration.ofSeconds(props.getCategoriesTtlSeconds())));
        configs.put(SEARCH_CACHE, base.entryTtl(Duration.ofSeconds(props.getSearchTtlSeconds())));
        configs.put(NEARBY_CACHE, base.entryTtl(Duration.ofSeconds(props.getNearbyTtlSeconds())));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(base)
                .withInitialCacheConfigurations(configs)
                .build();
    }

    @Bean("searchKeyGenerator")
    public KeyGenerator searchKeyGenerator() {
        return new Sha256KeyGenerator("search");
    }

    @Bean("nearbyKeyGenerator")
    public KeyGenerator nearbyKeyGenerator() {
        return new Sha256KeyGenerator("nearby");
    }

    private static final class Sha256KeyGenerator implements KeyGenerator {

        private final String namespace;

        private Sha256KeyGenerator(String namespace) {
            this.namespace = namespace;
        }

        @Override
        public Object generate(Object target, Method method, Object... params) {
            String canonical = namespace + ":" + method.getName() + ":" + canonicalize(params);
            return sha256Hex(canonical);
        }

        private static String canonicalize(Object... params) {
            StringBuilder sb = new StringBuilder();
            if (params == null) {
                return "";
            }
            for (int i = 0; i < params.length; i++) {
                if (i > 0) {
                    sb.append('|');
                }
                Object p = params[i];
                if (p == null) {
                    sb.append("null");
                } else {
                    sb.append(p.toString().trim().toLowerCase());
                }
            }
            return sb.toString();
        }

        private static String sha256Hex(String s) {
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] digest = md.digest(s.getBytes(StandardCharsets.UTF_8));
                StringBuilder out = new StringBuilder(digest.length * 2);
                for (byte b : digest) {
                    out.append(String.format("%02x", b));
                }
                return out.toString();
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("Unable to compute SHA-256", e);
            }
        }
    }
}
