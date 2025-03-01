package com.mafia.domain.game.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.mafia.domain.game.model.dto.EndGameInfoDto;
import com.mafia.domain.game.model.dto.GameInfoDto;
import com.mafia.domain.game.model.game.GamePhase;
import com.mafia.domain.game.service.GameService;
import com.mafia.domain.login.model.dto.AuthenticatedUser;
import com.mafia.global.common.model.dto.BaseResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Value;
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
@Tag(name = "Game Controller", description = "좀비 기반 마피아 게임의 로직을 처리하는 API입니다.")

public class GameController {

    private final GameService gameService;
    private final String gameVersion;

    public GameController(GameService gameService,
        @Value("${application.version}") String gameVersion){
        this.gameService = gameService;
        this.gameVersion = gameVersion;
    }

    /*
    TODO:
     1. 게임은 낮과 밤으로 구성되어 있고 각각의 행동이 다르다.
     2. 경찰은 직업을 알 수 있고, 의사는 살릴 수 있다.
     3. 게임이 종료되면 삭제 후 이전 방으로 되돌아간다.
     4. 게임이 끝나면 결과를 알려준다.
     5. 투표는 매일 낮 진행되며, 밤에는 투표가 없다.
     6. 살인은 매일 밤 진행되며, 경찰과 의사의 행동 또한 밤으로 제한한다.
     7. 돌연변이는 투표를 하지 못하며, 투표 시간에 죽일 사람을 지정한다.
     */

    @GetMapping("/{roomId}")
    @Operation(summary = "Get game", description = "방 ID로 게임 정보를 가져옵니다.")
    public ResponseEntity<BaseResponse<GameInfoDto>> getGame(@AuthenticationPrincipal AuthenticatedUser detail,
        @PathVariable Long roomId) {
        GameInfoDto gameInfo = gameService.getGameInfo(detail.getMemberId(), roomId);
        return ResponseEntity.ok(new BaseResponse<>(gameInfo));
    }

    @GetMapping("/{roomId}/ending")
    @Operation(summary = "Get EndSence Data", description = "방 ID로 게임 종료 데이터를 가져옵니다.")
    public ResponseEntity<BaseResponse<EndGameInfoDto>> getEndPlayers(@PathVariable Long roomId) {
        EndGameInfoDto gameInfo = gameService.getEndGamePlayers(roomId);
        return ResponseEntity.ok(new BaseResponse<>(gameInfo));
    }

    @DeleteMapping("/{roomId}")
    @Operation(summary = "Delete game", description = "방 ID로 게임을 삭제합니다.")
    public ResponseEntity<BaseResponse<String>> deleteGame(@PathVariable Long roomId)
        throws JsonProcessingException {
        gameService.deleteGame(roomId, gameVersion);
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

    @GetMapping("/{roomId}/skip")
    @Operation(summary = "Skip vote", description = "토론 시간을 단축합니다.(20초, 낮 토론 시간에만 가능합니다.)")
    public ResponseEntity<BaseResponse<String>> skipVote(@PathVariable Long roomId) {
        gameService.validatePhase(roomId, GamePhase.DAY_DISCUSSION);
        gameService.skipDiscussion(roomId, 20);
        return ResponseEntity.ok(new BaseResponse<>("Vote skipped in Room " + roomId + "."));
    }

    @PostMapping("/{roomId}/target/set")
    @Operation(summary = "set target player", description = "타겟을 설정합니다.(밤 페이즈)")
    public ResponseEntity<BaseResponse<String>> setTarget(@PathVariable Long roomId,
        @AuthenticationPrincipal AuthenticatedUser detail, @RequestParam Integer targetNo)
        throws JsonProcessingException {
        gameService.validatePhase(roomId, GamePhase.NIGHT_ACTION);
        String result = gameService.setTarget(roomId, detail.getMemberId(), targetNo);
        return ResponseEntity.ok(
            new BaseResponse<>(result));
    }
}
