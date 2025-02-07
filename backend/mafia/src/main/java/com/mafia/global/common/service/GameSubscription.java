package com.mafia.global.common.service;

import com.mafia.domain.chat.model.enumerate.ChatType;
import com.mafia.domain.chat.service.ChatSubscriber;
import com.mafia.domain.game.event.GameSubscriber;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class GameSubscription implements Subscription{

    private final RedisMessageListenerContainer redisMessageListenerContainer;
    private final ChatSubscriber chatSubscriber;
    private final GameSubscriber gameSubscriber;
    private final Map<String, ChannelTopic> activeTopics = new HashMap<>();

    /**
     * 🔥 새로운 Redis 구독 채널 생성
     */
    @Override
    public void subscribe(Long gameId) {
        for (ChatType type : ChatType.values()) {
            if(type == ChatType.ROOM) continue;
            String topicName = "game-" + gameId + "-" + type + "-chat";
            ChannelTopic topic = new ChannelTopic(topicName);

            if (!activeTopics.containsKey(topicName)) {
                redisMessageListenerContainer.addMessageListener(chatSubscriber, topic);
                activeTopics.put(topicName, topic);
                log.info("✅ Redis 게임 {} 채널 구독 시작: {}",type, topicName);
            }
        }
        String topicName = "game-" + gameId + "-system";
        ChannelTopic topic = new ChannelTopic(topicName);

        if (!activeTopics.containsKey(topicName)) {
            redisMessageListenerContainer.addMessageListener(gameSubscriber, topic);
            activeTopics.put(topicName, topic);
            log.info("✅ Redis 게임 System 채널 구독 시작: {}", topicName);
        }
    }

    /**
     * 🛑 Redis 구독 제거 (게임 종료 시)
     */
    @Override
    public void unsubscribe(Long gameId) {
        for (ChatType type : ChatType.values()) {
            if(type == ChatType.ROOM) continue;
            String topicName = "game-" + gameId + "-" + type + "-chat";
            ChannelTopic topic = activeTopics.remove(topicName);

            if (topic != null) {
                redisMessageListenerContainer.removeMessageListener(chatSubscriber, topic);
                log.info("❌ Redis 게임 {} 채널 구독 제거: {}",type, topicName);
            }
        }
        String topicName = "game-" + gameId + "-system";
        ChannelTopic topic = activeTopics.remove(topicName);

        if (topic != null) {
            redisMessageListenerContainer.removeMessageListener(gameSubscriber, topic);
            log.info("❌ Redis 게임 System 채널 구독 제거: {}", topicName);
        }
    }
}

