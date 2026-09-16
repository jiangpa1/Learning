package com.jiangpa;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class RedisConnectTest {
    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Test
    void test() {
        stringRedisTemplate.opsForValue().set("test:hello", "world");
        System.out.println(stringRedisTemplate.opsForValue().get("test:hello"));
    }
}