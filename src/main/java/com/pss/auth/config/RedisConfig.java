package com.pss.auth.config;

import com.pss.auth.service.CacheInvalidationListener;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.jedis.JedisClientConfiguration;
import org.springframework.data.redis.connection.jedis.JedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import redis.clients.jedis.JedisPoolConfig;

@Configuration
@EnableConfigurationProperties({RedisConfig.RedisProperties.class, RedisConfig.JedisPoolProperties.class})
public class RedisConfig {

    @ConfigurationProperties(prefix = "app.redis")
    public record RedisProperties(String host, int port, String username, String password, boolean ssl) {
        public RedisProperties {
            if (host == null) host = "localhost";
            if (port <= 0) port = 6379;
        }
    }

    @ConfigurationProperties(prefix = "app.redis.jedis.pool")
    public record JedisPoolProperties(int maxActive, int maxIdle, int minIdle) {
        public JedisPoolProperties {
            if (maxActive <= 0) maxActive = 16;
            if (maxIdle <= 0) maxIdle = 8;
            if (minIdle <= 0) minIdle = 4;
        }
    }

    @Bean
    public JedisConnectionFactory redisConnectionFactory(RedisProperties properties, JedisPoolProperties poolProps) {
        var config = new RedisStandaloneConfiguration(properties.host(), properties.port());
        if (properties.username() != null && !properties.username().isBlank()) {
            config.setUsername(properties.username());
        }
        if (properties.password() != null && !properties.password().isBlank()) {
            config.setPassword(properties.password());
        }

        // Configure Jedis connection pool
        var poolConfig = new JedisPoolConfig();
        poolConfig.setMaxTotal(poolProps.maxActive());
        poolConfig.setMaxIdle(poolProps.maxIdle());
        poolConfig.setMinIdle(poolProps.minIdle());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);

        var clientConfigBuilder = JedisClientConfiguration.builder()
                .usePooling()
                .poolConfig(poolConfig);

        if (properties.ssl()) {
            clientConfigBuilder.and().useSsl();
        }

        return new JedisConnectionFactory(config, clientConfigBuilder.build());
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

    // =========================================================================
    // Pub/Sub Listener for distributed cache invalidation
    // =========================================================================

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            JedisConnectionFactory connectionFactory,
            CacheInvalidationListener listener,
            @Value("${app.cache-invalidation.channel:auth:invalidation}") String channel) {
        var container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(listener, new ChannelTopic(channel));
        return container;
    }
}
