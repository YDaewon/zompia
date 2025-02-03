package com.mafia.domain.game.service;

import io.openvidu.java.client.*;
import java.util.Base64;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class VoiceService {

    private final String OPENVIDU_URL = "http://localhost:4443/";
    private final String SECRET = "MY_SECRET_KEY"; // docker-compose.yml에서 설정한 값

    private final OpenVidu openvidu = new OpenVidu(OPENVIDU_URL, SECRET);
    private final Map<Long, Session> gameSessions = new HashMap<>(); // 게임별 세션 저장
    private final Map<Long, Map<Integer, String>> playerTokens = new HashMap<>(); // 게임 내 플레이어별 토큰 저장

    /**
     * 게임 시작 시 OpenVidu 세션 생성
     */
    public String createSession(long gameId)
        throws OpenViduJavaClientException, OpenViduHttpException {
        Session session = openvidu.createSession();
        gameSessions.put(gameId, session);
        playerTokens.put(gameId, new HashMap<>());
        return session.getSessionId();
    }

    /**
     * 특정 플레이어에게 토큰 발급 (음성 채팅 참여)
     */
    public String generateToken(long gameId, int playerNo)
        throws OpenViduJavaClientException, OpenViduHttpException {
        if (!gameSessions.containsKey(gameId)) {
            throw new RuntimeException("Game session not found.");
        }

        Session session = gameSessions.get(gameId);
        ConnectionProperties properties = new ConnectionProperties.Builder()
            .type(ConnectionType.WEBRTC)
            .data("Player " + playerNo)
            .build();

        String token = session.createConnection(properties).getToken();
        playerTokens.get(gameId).put(playerNo, token);
        return token;
    }

    public String getConnectionId(long gameId, int playerId) {
        if (!playerTokens.containsKey(gameId) || !playerTokens.get(gameId).containsKey(playerId)) {
            throw new RuntimeException("Player token not found.");
        }

        // 🔥 OpenVidu의 특정 플레이어의 토큰을 가져와서 connectionId 확인
        String token = playerTokens.get(gameId).get(playerId);
        try {
            for (Connection connection : gameSessions.get(gameId).getConnections()) {
                if (connection.getToken().equals(token)) {
                    return connection.getConnectionId();
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to find connectionId for Player " + playerId);
        }

        throw new RuntimeException("Connection ID not found for Player " + playerId);
    }


    /**
     * 게임 종료 시 OpenVidu 세션 종료
     */
    public void closeSession(long gameId)
        throws OpenViduJavaClientException, OpenViduHttpException {
        if (gameSessions.containsKey(gameId)) {
            gameSessions.get(gameId).close();
            gameSessions.remove(gameId);
            playerTokens.remove(gameId);
        }
    }
}
