package com.mafia.domain.game.service;

import static com.mafia.global.common.model.dto.BaseResponseStatus.UNKNOWN_PHASE;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mafia.domain.game.event.GamePublisher;
import com.mafia.domain.game.model.dto.GameEndEvent;
import com.mafia.domain.game.model.dto.GameStartEvent;
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
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 게임의 진행을 관리하는 스케줄러 서비스 클래스.
 * 게임의 타이머를 관리하며 페이즈 전환을 수행한다.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GameScheduler {

    private final GameSeqRepository gameSeqRepository;
    private final GameRepository gameRepository;
    private final GameService gameService;
    private final GamePublisher gamePublisher;
    private final ObjectMapper objectMapper;

    /**
     * 게임별 개별 스케줄러를 관리하는 맵.
     */
    private final Map<Long, ScheduledFuture<?>> gameSchedulers = new ConcurrentHashMap<>();

    /**
     * 스케줄링을 수행하는 스레드 풀.
     */
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(8);
    // CPU 코어 개수를 모르겠네요...

    /**
     * 특정 게임(gameId)에 대한 스케줄러를 시작한다.
     *
     * @param event 게임 시작 이벤트 (GameStartEvent)
     */
    @EventListener
    public void startGameScheduler(GameStartEvent event) {
        Long gameId = event.getGameId();
        if (gameSchedulers.containsKey(gameId)) {
            log.info("[GameScheduler] 게임 " + gameId + "의 스케줄러가 이미 실행 중입니다.");
            return;
        }

        ScheduledFuture<?> scheduledTask = scheduler.scheduleAtFixedRate(() -> {
            try {
                processTimers(gameId);
            } catch (JsonProcessingException e) {
                e.printStackTrace();
            }
        }, 0, 1, TimeUnit.SECONDS);

        gameSchedulers.put(gameId, scheduledTask);
        System.out.println("[GameScheduler] 게임 " + gameId + "의 스케줄러가 시작되었습니다.");
    }

    /**
     * 특정 게임(gameId)에 대한 스케줄러를 종료한다.
     *
     * @param event 게임 종료 이벤트 (GameEndEvent)
     */
    @EventListener
    public void stopGameScheduler(GameEndEvent event) {
        Long gameId = event.getGameId();
        ScheduledFuture<?> scheduledTask = gameSchedulers.remove(gameId);
        if (scheduledTask != null) {
            scheduledTask.cancel(true);
            System.out.println("[GameScheduler] 게임 " + gameId + "의 스케줄러가 종료되었습니다.");
        }
    }

    /**
     * 특정 게임의 타이머를 처리하고 페이즈를 전환한다.
     *
     * @param gameId 게임 ID
     * @throws JsonProcessingException JSON 변환 오류 발생 시 예외 처리
     */
    public void processTimers(long gameId) throws JsonProcessingException {
        Long remainingTime = gameService.getTime(gameId);
        GamePhase phase = gameService.getPhase(gameId);

        if(remainingTime == 5 && phase == GamePhase.DAY_FINAL_STATEMENT){
            gameService.getFinalVoteResult(gameId);
        }
        
        if (remainingTime < 0) {
            advanceGamePhase(gameId);
        } else {
            gameSeqRepository.decrementTimer(gameId, 1);
        }

        // JSON 메시지 생성
        Map<String, String> timer = Map.of(
            "time", String.valueOf(remainingTime),
            "phase", String.valueOf(phase)
        );

        // JSON 변환
        String jsonMessage = objectMapper.writeValueAsString(timer);

        gamePublisher.publish("game-"+gameId+"-system", jsonMessage);
    }

    /**
     * 게임의 페이즈를 전환한다.
     *
     * @param gameId 게임 ID
     * @throws BusinessException 유효하지 않은 페이즈일 경우 예외 발생
     * @throws JsonProcessingException JSON 변환 오류 발생 시 예외 처리
     */
    public void advanceGamePhase(long gameId) throws JsonProcessingException {
        GamePhase curPhase = gameService.getPhase(gameId);
        Game game = gameService.findById(gameId);
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
                gameService.killPlayer(gameId);
                game.updateVoicePermissions("night"); // 좀비만 음성 채팅 활성화
                gameSeqRepository.savePhase(gameId, GamePhase.NIGHT_ACTION);
                gameSeqRepository.saveTimer(gameId, game.getSetting().getNightTimeSec());
            }
            case NIGHT_ACTION -> {
                game.getVotes().clear();
                gameService.killPlayer(gameId);
                game.roundInit();
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
