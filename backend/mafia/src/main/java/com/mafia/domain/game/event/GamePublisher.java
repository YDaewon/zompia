package com.mafia.domain.game.event;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

@Service
public class GamePublisher {

    private final StringRedisTemplate redisTemplate;

    public GamePublisher(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    public void publish(String topic, String message) {
        redisTemplate.convertAndSend(topic, message);
    }
}
