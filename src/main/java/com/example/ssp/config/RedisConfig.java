package com.example.ssp.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public RedisTemplate<String, Object> redisTemplate(RedisConnectionFactory factory) {
        RedisTemplate<String, Object> template = new RedisTemplate<>();

        // factory 由 Spring 根据 application.yml 中的 Redis 配置自动创建并注入
        template.setConnectionFactory(factory);

        // key 用字符串序列化，存进 Redis 的 key 是可读的（如 ssp:slot:slot-001）
        StringRedisSerializer stringSerializer = new StringRedisSerializer();
        template.setKeySerializer(stringSerializer);
        template.setHashKeySerializer(stringSerializer);  // Hash 结构的外层 key 也用字符串

        // 创建 JSON 转换器，专门给 Redis 序列化用
        ObjectMapper objectMapper = new ObjectMapper();
        // 注册 JavaTimeModule，让 ObjectMapper 能处理 LocalDateTime 类型
        objectMapper.registerModule(new JavaTimeModule());
        // 关闭时间戳格式，让 LocalDateTime 序列化成可读字符串（如 "2024-06-12T10:30:00"）
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // 把 ObjectMapper 包装成 Redis 能用的序列化器，Object.class 表示支持任意类型
        Jackson2JsonRedisSerializer<Object> jsonSerializer =
                new Jackson2JsonRedisSerializer<>(objectMapper, Object.class);
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);

        // 所有属性设置完毕，手动触发初始化（@Bean 方法里手动 new 的对象 Spring 不会自动调用）
        template.afterPropertiesSet();
        return template;
    }
}
