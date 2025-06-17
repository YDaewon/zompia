package com.mafia.domain.game.service;

import static com.mafia.global.common.model.dto.BaseResponseStatus.*;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mafia.domain.game.event.GamePublisher;
import com.mafia.domain.game.model.dto.EndGameInfoDto;
import com.mafia.domain.game.model.dto.GameEndEvent;
import com.mafia.domain.game.model.dto.GameInfoDto;
import com.mafia.domain.game.model.dto.GameStartEvent;
import com.mafia.domain.game.model.entity.GameLog;
import com.mafia.domain.game.model.game.Game;
import com.mafia.domain.game.model.game.GamePhase;
import com.mafia.domain.game.model.game.GameStatus;
import com.mafia.domain.game.model.game.Player;
import com.mafia.domain.game.model.game.Role;
import com.mafia.domain.game.repository.GameLogRepository;
import com.mafia.domain.game.repository.GameRepository;
import com.mafia.domain.game.repository.GameSeqRepository;
import com.mafia.domain.member.service.MemberService;
import com.mafia.domain.room.model.redis.RoomInfo;
import com.mafia.domain.room.service.RoomRedisService;
import com.mafia.global.common.exception.exception.BusinessException;
import com.mafia.global.common.service.GameSubscription;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * 게임 서비스 클래스 게임 관리와 관련된 비즈니스 로직을 처리합니다. 작성자: YDaewon
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GameService {

    private final RoomRedisService roomService;
    private final MemberService memberService;
    // private final GameScheduler gameScheduler;
    private final ApplicationEventPublisher applicationEventPublisher;
    private final GameRepository gameRepository; // 게임 데이터를 관리하는 리포지토리
    private final GameSeqRepository gameSeqRepository; // 게임 상태 및 시간 정보를 관리하는 리포지토리
    private final GameLogRepository gameLogRepository;
    private final VoiceService voiceService; // 🔥 OpenVidu 연동 추가
    private final GamePublisher gamePublisher; // Game Websocket
    private final GameSubscription subscription;
    private final ObjectMapper objectMapper;



    /**
     * 게임 조회
     *
     * @param gameId 방 ID
     * @return 게임 객체
     * @throws BusinessException 게임이 존재하지 않을 경우 예외 발생
     */
    public GameInfoDto getGameInfo(Long memberId, long gameId) {
        Game game = findById(gameId);
        return new GameInfoDto(memberId, game);
    }

    /**
     * 종료씬 플레이어 목록 조회
     *
     * @param gameId 방 ID
     * @return 게임 객체
     * @throws BusinessException 게임이 존재하지 않을 경우 예외 발생
     */
    public EndGameInfoDto getEndGamePlayers(long gameId) {
        Game game = findById(gameId);
        if(game.getGameStatus() == GameStatus.PLAYING){
            throw new BusinessException(GAME_IS_NOT_END);
        }
        return new EndGameInfoDto(game);
    }

    /**
     * 게임 조회
     *
     * @param gameId 방 ID
     * @return 게임 객체
     * @throws BusinessException 게임이 존재하지 않을 경우 예외 발생
     */
    public Game findById(long gameId) {
        return gameRepository.findById(gameId)
            .orElseThrow(() -> new BusinessException(GAME_NOT_FOUND));
    }

    /**
     * 게임 시작
     *
     * @param gameId 방 ID를 그대로 사용한다.
     * @throws BusinessException 이미 시작된 게임이거나 플레이어가 부족할 경우 예외 발생
     */
    public boolean startGame(long gameId) {
        gameRepository.findById(gameId).ifPresent(game -> {
            throw new BusinessException(GAME_ALREADY_START);
        });
        Game game = makeGame(gameId);

        log.info("Game {} created.", gameId);
        game.startGame();
        gameSeqRepository.savePhase(gameId, GamePhase.DAY_DISCUSSION); // 낮 토론 시작
        gameSeqRepository.saveTimer(gameId, game.getSetting().getDayDisTimeSec()); // 설정된 시간

        // 🔥 OpenVidu 세션 생성
        try {
            String sessionId = voiceService.createSession(gameId);
            log.info("OpenVidu Session {} created for Game {}", sessionId, gameId);

            // 🔥 모든 플레이어에게 토큰 발급
            for (Long playerId : game.getPlayers().keySet()) {
                String token = voiceService.generateToken(gameId, playerId);
                game.getPlayers().get(playerId).setOpenviduToken(token);
                log.info("Token issued for Player {}: {}", playerId, token);
            }
        } catch (Exception e) {
            log.error("Failed to create OpenVidu session: {}", e.getMessage());
        }

        //Redis 채팅방 생성
        subscription.subscribe(gameId);
        gameRepository.save(game);
        log.info("Game started in Room {}: Phase set to {}, Timer set to {} seconds",
            gameId, GamePhase.DAY_DISCUSSION, game.getSetting().getDayDisTimeSec());
        applicationEventPublisher.publishEvent(new GameStartEvent(gameId));
        return true;
    }

    private Game makeGame(long roomId) {
        RoomInfo roominfo = roomService.findById(roomId);

        Game game = new Game(roomId, roominfo.getGameOption());
        game.setMap_players(roominfo.getMemberMapping());
        // 게임에 참가할 플레이어를 추가한다.
        roominfo.getParticipant().values().forEach(game::addPlayer);

        return game;
    }


    /**
     * 게임 삭제
     *
     * @param gameId 방 ID
     * @throws BusinessException 게임이 존재하지 않을 경우 예외 발생
     */
    @Transactional
    public void deleteGame(long gameId, String version) throws JsonProcessingException {
        Game game = findById(gameId);
        Optional.ofNullable(gameSeqRepository.getTimer(gameId))
            .orElseThrow(() -> new BusinessException(PHASE_NOT_FOUND));
        Optional.ofNullable(gameSeqRepository.getPhase(gameId))
            .orElseThrow(() -> new BusinessException(PHASE_NOT_FOUND));
        List<Player> players =  new ArrayList<>(game.getPlayers().values());

        memberService.recordMembers(players, game.getGameStatus());

        GameLog gameLog = new GameLog(gameId, game.getGameStatus(),
            game.getPlayers().size(), version);


        gameLogRepository.save(gameLog);

        // 게임 삭제 로그 전송
        String jsonMessage = objectMapper.writeValueAsString(
            Map.of("backroom", true)
        );
        gamePublisher.publish("game-" + game.getGameId() + "-system", jsonMessage);


        // 게임 스레드 풀 반납
        applicationEventPublisher.publishEvent(new GameEndEvent(gameId));

        //Redis 채팅 채널 제거
        subscription.unsubscribe(gameId);

        gameRepository.delete(gameId);
        gameSeqRepository.delete(gameId);

        // 🔥 OpenVidu 세션 종료
        try {
            voiceService.closeSession(gameId);
            log.info("OpenVidu Session closed for Game {}", gameId);
        } catch (Exception e) {
            log.error("Failed to close OpenVidu session: {}", e.getMessage());
        }

        log.info("Room {} deleted.", gameId);
    }

    /**
     * 투표 처리
     *
     * @param gameId   방 ID
     * @param playerNo 투표를 하는 사용자 ID
     * @param targetNo 투표 대상 사용자 번호
     * @throws BusinessException 유효하지 않은 투표 조건일 경우 예외 발생
     */
    public void vote(long gameId, Long playerNo, Integer targetNo) { // 투표 sync 고려
        Game game = findById(gameId);
        if (game != null) {
            if (targetNo == -1) // 기권 처리
            {
                log.info("[Game{}] Player {} is abstention", gameId, playerNo);
                return;
            }
            if (game.getPlayers().get(playerNo).isDead()) {
                throw new BusinessException(DEAD_CANNOT_VOTE);
            }
            if (game.getPlayers().get(playerNo).getRole() == Role.MUTANT) {
                throw new BusinessException(MUTANT_CANNOT_VOTE);
            }

            game.vote(playerNo, targetNo);
            gameRepository.save(game);
            log.info("Player {} voted for Target {} in Room {}.", playerNo, targetNo, gameId);
        } else {
            log.warn("Room {} does not exist.", gameId);
        }
    }

    /**
     * 최종 찬반 투표: 보내는거 자체가 수락임
     *
     * @param gameId 방 ID
     *
     */
    public void finalVote(long gameId) {
        Game game = findById(gameId);
        game.finalVote();

        gameRepository.save(game);
    }


    /**
     * 최종 찬반 투표 결과 반환
     * -> GameScheduler로 옮기기
     * @param gameId 방 ID
     *
     */
    protected void getFinalVoteResult(long gameId) throws JsonProcessingException {
        Game game = findById(gameId);
        boolean isKill = game.finalvoteResult();

        String topic = "game-" + gameId + "-system";
        // JSON 메시지 생성 및 publish
        String message = objectMapper.writeValueAsString(
            Map.of("votekill", isKill)
        );
        gamePublisher.publish(topic, message);

        if (isKill) {
            log.info("[Game{}] Vote Kill!!!!!", gameId);
            gameRepository.save(game);
        }
        else log.info("[Game{}] No one is selected", gameId);
    }


    /**
     * @param game  방 ID가 있는 이벤트 객체
     */
    protected void killPlayer(Game game) throws JsonProcessingException {
        Integer healedPlayer = game.getHealTarget();
        List<Integer> killList = game.killProcess();

        Map<String, String> message = new HashMap<>();
        //의사
        if (healedPlayer != 0 && killList != null && (killList.isEmpty() || !killList.contains(
            healedPlayer))) {
            message.put("heal", String.valueOf(healedPlayer));
            log.info("Game[{}] 플레이어 " + healedPlayer + " 이(가) 의사의 치료로 살아남았습니다!", healedPlayer);
        }
        // 좀비
        if (killList != null && !killList.isEmpty()) {
            // JSON 형태로 메시지 구성
            String deaths = killList.stream()
                .map(String::valueOf)
                .collect(Collectors.joining(", "));
            message.put("death", deaths);
        }

        if (!message.isEmpty()) {
            String jsonMessage = objectMapper.writeValueAsString(message);
            // Redis Pub/Sub 전송
            gamePublisher.publish("game-" + game.getGameId() + "-system", jsonMessage);
        }
        gameRepository.save(game);
    }

    /**
     * 타겟 지정(경찰, 의사, 좀비, 돌연변이)(밤에만 가능)
     *
     * @param gameId   방 ID
     * @param playerNo 사용자 ID
     * @param targetNo 죽일 사용자 번호
     * @throws BusinessException 유효하지 않은 조건일 경우 예외 발생
     */
    public String setTarget(long gameId, Long playerNo, Integer targetNo)
        throws JsonProcessingException {
        Game game = findById(gameId);
        Role myrole = game.getPlayers().get(playerNo).getRole();
        log.info("Service set Target 실행");
        String result = "";
        if (myrole == Role.ZOMBIE) {
            game.specifyTarget(Role.ZOMBIE, targetNo);
            result = targetNo + "플레이어는 감염 타겟이 되었습니다.";
            String topic = "game-" + gameId + "-maifa-system";
            // JSON 메시지 생성 및 publish
            String message = objectMapper.writeValueAsString(
                Map.of("zombiepick", targetNo)
            );
            gamePublisher.publish(topic, message);
        } else if (myrole == Role.MUTANT) {
            game.specifyTarget(Role.MUTANT, targetNo);
            result = targetNo + "플레이어는 돌연변이 타겟이 되었습니다.";
        } else if (myrole == Role.POLICE) {
            Role findrole = game.findRole(targetNo);
            result = targetNo + "의 직업은 " + findrole + "입니다.";
        } else if (myrole == Role.PLAGUE_DOCTOR) {
            if (game.getSetting().getDoctorSkillUsage() == 0) {
                result = "남은 백신이 없습니다.";
            } else {
                int heal_cnt = game.heal(targetNo);
                result = targetNo + "을 살리기로 했습니다. 남은 백신은 " + heal_cnt + "개 입니다.";
            }
        }

        log.info("[Game{}] Player{} set the target of {}", gameId, targetNo, myrole);
        gameRepository.save(game);
        return result.isEmpty() ? "setTarget 요청 실패" : result;
    }

    /**
     * 토론 시간 스킵
     *
     * @param gameId 방 ID
     * @param sec    단축할 시간 (초 단위)
     * @throws BusinessException 남은 시간이 적을 경우 예외 발생
     */
    public void skipDiscussion(long gameId, int sec) {
        int now = gameSeqRepository.getTimer(gameId).intValue();
        if (now - sec < 15) {
            throw new BusinessException(GAME_TIME_OVER);
        }

        gameSeqRepository.decrementTimer(gameId, sec);
    }

    /**
     * 페이즈 별 API 호출 제한
     *
     * @param gameId        방 ID
     * @param expectedPhase 예상되는 페이즈
     * @throws BusinessException 현재 페이즈와 예상 페이즈가 다를 경우 예외 발생
     */
    public void validatePhase(long gameId, GamePhase expectedPhase) {
        GamePhase currentPhase = gameSeqRepository.getPhase(gameId);
        if (currentPhase != expectedPhase) {
            throw new BusinessException(INVALID_PHASE);
        }
    }
}
