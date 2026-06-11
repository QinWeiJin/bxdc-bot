package com.lobsterai.skillgateway.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.format.DateTimeFormatter;

/**
 * Jackson 序列化配置。
 * <p>
 * 直接覆盖 Spring Boot 自动配置的 ObjectMapper Bean，
 * 显式注册 JavaTimeModule 并禁用 WRITE_DATES_AS_TIMESTAMPS，
 * 让 LocalDateTime / LocalDate 字段走 JSR-310 序列化器（避免被当成 bean 序列化导致
 * "Unsupported field: OffsetSeconds" 错误）。
 */
@Configuration
public class JacksonConfig {

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper om = new ObjectMapper();
        JavaTimeModule javaTimeModule = new JavaTimeModule();
        // 用我们项目统一的 Asia/Shanghai 格式，替代默认 timestamp 数组
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSSXXX")
                .withZone(java.time.ZoneId.of("Asia/Shanghai"));
        javaTimeModule.addSerializer(new LocalDateTimeSerializer(formatter));
        om.registerModule(javaTimeModule);
        om.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        om.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        return om;
    }
}
