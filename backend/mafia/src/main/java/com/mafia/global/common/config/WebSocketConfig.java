package com.mafia.global.common.config;

import com.mafia.global.common.handler.StompHandler;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

@Configuration
@EnableWebSocketMessageBroker
@RequiredArgsConstructor
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    private final StompHandler stompHandler;

    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
//        registry.addEndpoint("/ws-mafia") // 클라이언트가 연결할 WebSocket 엔드포인트
//            .setAllowedOriginPatterns("*")
//            .withSockJS();  // 최종 넣을 때는 뺀다.

        registry.addEndpoint("/ws-mafia") // 클라이언트가 연결할 WebSocket 엔드포인트
            .setAllowedOriginPatterns("*")
            .setHandshakeHandler(new DefaultHandshakeHandler()); // 🔥 WebSocket 핸드셰이크 지원 추가
    }

    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        registry.enableSimpleBroker("/topic");  // 🔥 클라이언트가 구독할 경로
        registry.setApplicationDestinationPrefixes("/app");      // 🔥 클라이언트가 메시지를 보낼 경로
    }

    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(stompHandler);
    }
}
