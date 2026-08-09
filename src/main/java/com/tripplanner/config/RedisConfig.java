package com.tripplanner.config;

import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.jsontype.BasicPolymorphicTypeValidator;
import com.fasterxml.jackson.databind.jsontype.PolymorphicTypeValidator;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

/**
 * REDIS CONFIGURATION
 *
 * WHY CUSTOM SERIALIZATION:
 *   Spring's default Redis serializer uses Java's native ObjectOutputStream
 *   (binary format). This is problematic:
 *     ❌ Unreadable in Redis CLI (debug is impossible)
 *     ❌ Breaks when class fields change (serialVersionUID mismatch)
 *     ❌ Cannot be consumed by non-Java services
 *
 *   We use Jackson2JsonRedisSerializer → stores as human-readable JSON:
 *     ✅ Debuggable via redis-cli GET "dashboard:Delhi:Jaipur:4:4:petrol_car"
 *     ✅ Survives minor DTO field additions (Jackson ignores unknown fields)
 *     ✅ Interoperable with any language
 *
 * KEY FORMAT:
 *   Keys are plain Strings (StringRedisSerializer).
 *   Values are JSON (Jackson2JsonRedisSerializer with Object.class).
 *   This allows us to store any DTO without creating per-type RedisTemplates.
 */
@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);

        // Keys stored as plain UTF-8 strings (readable in redis-cli)
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);

        // Values stored as JSON with type metadata
        // Type metadata (@class field in JSON) is required so Jackson knows
        // which class to deserialize back to when reading from Redis.
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(buildObjectMapper(), Object.class);

        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        template.afterPropertiesSet();
        return template;
    }

    /**
     * Custom ObjectMapper for Redis serialization.
     * Separate from Spring MVC's ObjectMapper to avoid polluting HTTP responses
     * with Redis-internal type metadata (@class fields).
     */
    private ObjectMapper buildObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Support for Java 8 Date/Time API (LocalDate, LocalDateTime)
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // CRITICAL: Enable default typing so Jackson includes the concrete
        // class name in serialized JSON. Required to deserialize Object.class back
        // to the correct type (e.g., DashboardResponse).
        //
        // We use BasicPolymorphicTypeValidator to restrict which classes can be
        // deserialized — prevents Jackson Deserialization Gadget attacks.
        PolymorphicTypeValidator ptv = BasicPolymorphicTypeValidator
                .builder()
                .allowIfBaseType(Object.class)
                .build();

        mapper.activateDefaultTyping(ptv, ObjectMapper.DefaultTyping.NON_FINAL,
                JsonTypeInfo.As.PROPERTY);

        return mapper;
    }
}
