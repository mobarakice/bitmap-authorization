package com.pss.auth.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @ConfigurationProperties(prefix = "app.redis")
    public record RedisProperties(String host, int port, String username, String password, boolean ssl) {
        public RedisProperties {
            if (host == null) host = "localhost";
            if (port <= 0) port = 6379;
        }
    }

    @Bean
    public JedisConnectionFactory redisConnectionFactory(RedisProperties properties) {
        var config = new RedisStandaloneConfiguration(properties.host(), properties.port());
        if (properties.username() != null && !properties.username().isBlank()) {
            config.setUsername(properties.username());
        }
        if (properties.password() != null && !properties.password().isBlank()) {
            config.setPassword(properties.password());
        }

        if (properties.ssl()) {
            var clientConfig = org.springframework.data.redis.connection.jedis.JedisClientConfiguration.builder()
                    .useSsl()
                    .build();
            return new JedisConnectionFactory(config, clientConfig);
        }

        return new JedisConnectionFactory(config);
    }

    @Bean
    public RedisTemplate<String, byte[]> redisTemplate(JedisConnectionFactory connectionFactory) {
        var template = new RedisTemplate<String, byte[]>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.byteArray());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(new StringRedisSerializer());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public StringRedisTemplate stringRedisTemplate(JedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }
}
