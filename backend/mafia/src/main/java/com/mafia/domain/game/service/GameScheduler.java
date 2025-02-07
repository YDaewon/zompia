package com.mafia.domain.game.service;

import static com.mafia.global.common.model.dto.BaseResponseStatus.UNKNOWN_PHASE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mafia.domain.game.event.GamePublisher;
import com.mafia.domain.game.model.game.Game;
import com.mafia.domain.game.model.game.GamePhase;
import com.mafia.domain.game.repository.GameRepository;
import com.mafia.domain.game.repository.GameSeqRepository;
import com.mafia.global.common.exception.exception.BusinessException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
@Slf4j
public class GameScheduler {

    @Lazy
    private final GameSeqRepository gameSeqRepository;
    private final GameRepository gameRepository;
    private final GamePublisher gamePublisher;
    private final ObjectMapper objectMapper;

    // 게임별 개별 스케줄러 관리
    private final Map<Long, ScheduledFuture<?>> gameSchedulers = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
    // CPU 코어 개수를 모르겠네요...

    /**
     * 특정 게임(roomId)에 대한 스케줄러 시작
     */
    public void startGameScheduler(long roomId) {
        if (gameSchedulers.containsKey(roomId)) {
            log.info("[GameScheduler] 게임 " + roomId + "의 스케줄러가 이미 실행 중입니다.");
            return;
        }

        ScheduledFuture<?> scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                processTimers(roomId);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);

        gameSchedulers.put(roomId, scheduledTask);
        System.out.println("[GameScheduler] 게임 " + roomId + "의 스케줄러가 시작되었습니다.");
    }

    /**
     * 특정 게임(roomId)에 대한 스케줄러 종료
     */
    public void stopGameScheduler(long roomId) {
        ScheduledFuture<?> scheduledTask = gameSchedulers.remove(roomId);
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            System.out.println("[GameScheduler] 게임 " + roomId + "의 스케줄러가 종료되었습니다.");
        }
    }

    public void processTimers(long roomId) throws JsonProcessingException {
        Long remainingTime = gameSeqRepository.getTimer(roomId);
        GamePhase phaze = gameSeqRepository.getPhase(roomId);

        if (remainingTime == null || remainingTime < 0) {
            advanceGamePhase(roomId);
        } else {
            gameSeqRepository.decrementTimer(roomId, 1);
        }

        // JSON 메시지 생성
        Map<String, String> timer = Map.of(
            "time", String.valueOf(remainingTime),
            "phaze", String.valueOf(phaze)
        );

        // JSON 변환
        String jsonMessage = objectMapper.writeValueAsString(timer);

        gamePublisher.publish("game-"+roomId+"-system", jsonMessage);
    }

    /**
     * 페이즈 전환
     *
     * @param gameId 방 ID
     * @throws BusinessException 유효하지 않은 페이즈일 경우 예외 발생
     */
    public void advanceGamePhase(long gameId) throws JsonProcessingException {
        GamePhase curPhase = gameSeqRepository.getPhase(gameId);
        Game game = gameRepository.findById(gameId).orElse(null);
        if (game == null || curPhase == null) {
            log.error("Game[{}] not found", gameId);
            return;
        }

        switch (curPhase) {
            case DAY_DISCUSSION -> {
                gameSeqRepository.savePhase(gameId, GamePhase.DAY_VOTE);
                gameSeqRepository.saveTimer(gameId, 60);
            }
            case DAY_VOTE -> {
                if(game.voteResult() == -1){
                    game.updateVoicePermissions("night"); // 좀비만 음성 채팅 활성화
                    gameSeqRepository.savePhase(gameId, GamePhase.NIGHT_ACTION);
                    gameSeqRepository.saveTimer(gameId, game.getSetting().getNightTimeSec());
                } else {
                    gameSeqRepository.savePhase(gameId, GamePhase.DAY_FINAL_STATEMENT);
                    gameSeqRepository.saveTimer(gameId, 30);
                }
            }
            case DAY_FINAL_STATEMENT -> {
                gameSeqRepository.savePhase(gameId, GamePhase.DAY_FINAL_VOTE);
                gameSeqRepository.saveTimer(gameId, 20);
            }
            case DAY_FINAL_VOTE -> {
                game.updateVoicePermissions("night"); // 좀비만 음성 채팅 활성화
                gameSeqRepository.savePhase(gameId, GamePhase.NIGHT_ACTION);
                gameSeqRepository.saveTimer(gameId, game.getSetting().getNightTimeSec());
            }
            case NIGHT_ACTION -> {
                game.getVotes().clear();
                game.updateVoicePermissions("day"); // 모든 생존자 음성 채팅 활성화 (토론)
                gameSeqRepository.savePhase(gameId, GamePhase.DAY_DISCUSSION);
                gameSeqRepository.saveTimer(gameId, game.getSetting().getDayDisTimeSec());
            }
            default -> throw new BusinessException(UNKNOWN_PHASE);
        }

        gameRepository.save(game);
        log.info("Game phase advanced in Room {}: New Phase = {}, Timer = {} seconds",
            gameId, gameSeqRepository.getPhase(gameId), gameSeqRepository.getTimer(gameId));
    }


}
