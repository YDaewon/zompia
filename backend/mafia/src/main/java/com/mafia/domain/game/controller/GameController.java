package com.mafia.domain.game.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mafia.domain.game.model.game.Game;
import com.mafia.domain.game.model.game.GamePhase;
import com.mafia.domain.game.model.game.Player;
import com.mafia.domain.game.model.game.STATUS;
import com.mafia.domain.game.service.GameService;
import com.mafia.domain.login.model.dto.AuthenticatedUser;
import com.mafia.global.common.model.dto.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.HashMap;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/game")
@RequiredArgsConstructor
@Tag(name = "Game Controller", description = "좀비 기반 마피아 게임의 로직을 처리하는 API입니다.")

public class GameController {

    private final GameService gameService;

    /*
    TODO:
     1. 게임은 낮과 밤으로 구성되어 있고 각각의 행동이 다르다.
     2. 경찰은 직업을 알 수 있고, 의사는 살릴 수 있다.
     3. 게임이 종료되면 삭제 후 이전 방으로 되돌아간다.
     4. 게임이 끝나면 결과를 알려준다.
     5. 투표는 매일 낮 진행되며, 밤에는 투표가 없다.
     6. 살인은 매일 밤 진행되며, 경찰과 의사의 행동 또한 밤으로 제한한다.
     7. 돌연변이는 투표를 하지 못하며, 투표 시간에 죽일 사람을 지정한다.
     8. 경찰은 좀비를 찾게 되면 투표권이 없어진다.
     */

    @GetMapping("/{roomId}")
    @Operation(summary = "Get game", description = "방 ID로 게임 정보를 가져옵니다.")
    public ResponseEntity<BaseResponse<Game>> getGame(@PathVariable Long roomId) {
        Game game = gameService.findById(roomId);
        return ResponseEntity.ok(new BaseResponse<>(game));
    }

    @GetMapping("/{roomId}/player")
    @Operation(summary = "Get game", description = "플레이어의 정보를 가져옵니다.")
    public ResponseEntity<BaseResponse<Player>> getGame(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser detail) {
        Player player = gameService.findMemberByGame(roomId, detail.getMemberId());
        return ResponseEntity.ok(new BaseResponse<>(player));
    }

    @DeleteMapping("/{roomId}")
    @Operation(summary = "Delete game", description = "방 ID로 게임을 삭제합니다.")
    public ResponseEntity<BaseResponse<String>> deleteGame(@PathVariable Long roomId) {
        gameService.deleteGame(roomId);
        return ResponseEntity.ok(new BaseResponse<>("Room " + roomId + " deleted."));
    }

    @PostMapping("/{roomId}/vote")
    @Operation(summary = "Vote", description = "유저 ID와 타겟 ID를 받아 투표합니다.(투표 시간에만 가능합니다.")
    public ResponseEntity<BaseResponse<String>> vote(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser detail, @RequestParam Integer targetNo) {
        gameService.validatePhase(roomId, GamePhase.DAY_VOTE);
        gameService.vote(roomId, detail.getMemberId(), targetNo);
        return ResponseEntity.ok(new BaseResponse<>(
            "Player " + detail.getMemberId() + " voted for " + targetNo + " in Room " + roomId + "."));
    }

    @GetMapping("/{roomId}/finalvote")
    @Operation(summary = "Vote", description = "각유저의 투표 대상 처형을 최종 투표합니다.(마지막 투표 시간에만 가능합니다.")
    public ResponseEntity<BaseResponse<String>> vote(@PathVariable Long roomId) {
        gameService.validatePhase(roomId, GamePhase.DAY_FINAL_VOTE);
        gameService.finalVote(roomId);
        return ResponseEntity.ok(new BaseResponse<>("난 찬성!"));
    }

    @GetMapping("/{roomId}/voteresult")
    @Operation(summary = "Get vote result", description = "투표 집계 결과를 가져옵니다(테스트 용)")
    public ResponseEntity<BaseResponse<Integer>> getVoteResult(@PathVariable Long roomId) {
        return ResponseEntity.ok(new BaseResponse<>(gameService.getVoteResult(roomId)));
    }

    @GetMapping("/{roomId}/skip")
    @Operation(summary = "Skip vote", description = "토론 시간을 단축합니다.(20초, 낮 토론 시간에만 가능합니다.)")
    public ResponseEntity<BaseResponse<String>> skipVote(@PathVariable Long roomId) {
        gameService.validatePhase(roomId, GamePhase.DAY_DISCUSSION);
        gameService.skipDiscussion(roomId, 20);
        return ResponseEntity.ok(new BaseResponse<>("Vote skipped in Room " + roomId + "."));
    }

    @GetMapping("/{gameId}/kill")
    @Operation(summary = "Vote kill player", description = "타겟이 된 플레이어를 사망 처리합니다.") // 테스트용
    public ResponseEntity<BaseResponse<String>> killVote(@PathVariable Long gameId)
        throws JsonProcessingException {
        boolean life = gameService.killPlayer(gameId);
        if (life) {
            return ResponseEntity.ok(new BaseResponse<>("killed in Room " + gameId + "."));
        } else {
            return ResponseEntity.ok(new BaseResponse<>("saved in Room " + gameId + "."));
        }
    }

    @PostMapping("/{roomId}/target/set")
    @Operation(summary = "set target player", description = "타겟을 설정합니다.(밤 페이즈)")
    public ResponseEntity<BaseResponse<String>> setTarget(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser detail, @RequestParam Integer targetNo) {
        gameService.validatePhase(roomId, GamePhase.NIGHT_ACTION);
        gameService.setTarget(roomId, detail.getMemberId(), targetNo);
        return ResponseEntity.ok(
            new BaseResponse<>("User " + targetNo + " set as target in Room " + roomId + "."));
    }

    @GetMapping("/{roomId}/isEnd")
    @Operation(summary = "Check game over", description = "게임이 끝났는지 확인합니다.")
    public ResponseEntity<BaseResponse<STATUS>> isEnd(@PathVariable Long roomId) {
        STATUS status = gameService.isEnd(roomId);
        if (status != STATUS.PLAYING) {
            /*
             * 게임 로그 저장 기능 구현하기
             * */
            gameService.deleteGame(roomId);
        }
        return ResponseEntity.ok(new BaseResponse<>(status));
    }

    @GetMapping("/{roomId}/status")
    @Operation(summary = "Check game status", description = "게임의 현재 상태와 남은 시간을 확인합니다.")
    public ResponseEntity<BaseResponse<?>> getStatus(@PathVariable long roomId) {
        GamePhase phase = gameService.getPhase(roomId);
        Long Time = gameService.getTime(roomId);

        Map<String, Object> response = new HashMap<>();
        response.put("currentphase", phase);
        response.put("remainingtime", Time);

        return ResponseEntity.ok(new BaseResponse<>(response));
    }
}
