package com.example.storeflow;

import org.mockito.Mockito;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;

/**
 * 测试用 Redis 配置：用懒连接的 Lettuce 工厂（建上下文不拨号即可满足装配），
 * 并覆盖生产侧 RedisTemplate 为深度桩——所有 opsForXxx() 的链式读写默认返回安全空对象，
 * 让区域缓存调用成为空操作、不依赖真实 Redis；getConnectionFactory() 返回懒工厂，
 * 以满足 Spring Data Redis KeyValueAdapter 的装配。
 */
@TestConfiguration
public class TestRedisConfig {

    @Bean
    public LettuceConnectionFactory lettuceConnectionFactory() {
        // 懒连接：afterPropertiesSet 不会真正建立 socket
        return new LettuceConnectionFactory(new RedisStandaloneConfiguration("localhost", 6507));
    }

    @Bean
    @SuppressWarnings("unchecked")
    public RedisTemplate<String, Object> redisTemplate(LettuceConnectionFactory factory) {
        RedisTemplate<String, Object> mock = Mockito.mock(RedisTemplate.class, Mockito.RETURNS_DEEP_STUBS);
        Mockito.when(mock.getConnectionFactory()).thenReturn(factory);
        return mock;
    }
}
