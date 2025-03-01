package com.mafia.domain.game.model.game;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.mafia.domain.room.model.redis.Participant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Getter
@Setter
@NoArgsConstructor
@Slf4j
@Schema(description = "게임의 상태와 관련된 정보를 포함하는 클래스")
public class Game implements Serializable { // 필드정리

    private static final long serialVersionUID = 1L;

    @Schema(description = "게임 방 ID", example = "12345")
    @JsonProperty("game_id")
    private long gameId;

    @Schema(description = "게임에 참여한 플레이어 정보", example =
        "{101: {\"name\": \"Player1\"}, 102: {\"name\": \"Player2\"}}")
    private Map<Long, Player> players;

    @Schema(description = "플레이어 매핑 정보", example =
        "{1: 10214L, 2: 2165L}")
    private Map<Integer, Long> map_players;

    @Schema(description = "플레이어들의 투표 정보", example = "{101: 102, 103: 104}")
    private Map<Long, Integer> votes;

    @Schema(description = "최종 찬반 투표 수", example = "5")
    private int final_vote;

    @Schema(description = "게임의 현재 상태", example = "STARTED",
        allowableValues = {"PLAYING", "CITIZEN_WIN", "ZOMBIE_WIN", "MUTANT_WIN"})
    private GameStatus gameStatus;

    @Schema(description = "현재 라운드에서 의사가 치료 대상으로 지정한 플레이어의 ID", example = "101")
    private Integer healTarget = 0;

    @Schema(description = "각 직업 별 kill 타겟 매핑", example = "102")
    private Map<String, Integer> killTarget;

    @Schema(description = "게임 옵션")
    private GameOption setting;

    public Integer getPlayerNoByMemberId(Long memberId) {
        return map_players.entrySet().stream()
            .filter(entry -> entry.getValue().equals(memberId)) // memberId와 일치하는 값 찾기
            .map(Map.Entry::getKey) // 해당 키(플레이어 번호) 가져오기
            .findFirst()
            .orElse(null); // 없으면 null 반환
    }

    public Game(long roomId, GameOption setting) {
        this.gameId = roomId;
        this.players = new HashMap<>();
        this.votes = new HashMap<>();
        this.map_players = new HashMap<>();
        this.healTarget = 0;
        this.killTarget = new HashMap<>();
        this.setting = setting; // <- POST CONSTRUCT
    }

    public void roundInit() {
        this.votes.clear();
        this.final_vote = 0;
        this.healTarget = 0;
        this.killTarget.clear();
        log.info("round 진행- 초기화완료");
    }

    public void addPlayer(Participant participant) {
        for (Player p : players.values()) {
            if (p.getMemberId().equals(participant.getMemberId())) {
                log.info("[Game{}] User {} is already in the game", gameId,
                    participant.getMemberId());
                return;
            }
        }
        Player player = new Player(participant);
        players.put(participant.getMemberId(), player);
    }

    public void startGame() {
        this.gameStatus = GameStatus.PLAYING;
        // 1. 직업 분배
        List<Role> role = new ArrayList<>();
        init_role(role);
        int rcnt = 0;
        for (Map.Entry<Long, Player> entry : players.entrySet()) {
            Player player = entry.getValue();
            Role userRole = role.get(rcnt);

            player.setRole(userRole);
            player.subscribe("game-" + gameId + "-system");
            player.subscribe("game-" + gameId + "-day-chat");

            if (userRole == Role.ZOMBIE) {
                player.subscribe("game-" + gameId + "-night-chat");
                player.subscribe("game-" + gameId + "-mafia-system");
            }

            if (userRole == Role.MUTANT) {
                player.setEnableVote(false);
            }
            rcnt++;
        }
        log.info("[Game{}] Role distribution is completed", gameId);
    }

    private void init_role(List<Role> role) {
        for (int i = 0; i < setting.getZombie(); i++) {
            role.add(Role.ZOMBIE);
        }
        if (setting.getMutant() > 0 && Math.random() < 0.5) {
            role.add(Role.MUTANT);
        } else {
            setting.setMutant(0);
        }
        role.add(Role.POLICE);
        role.add(Role.PLAGUE_DOCTOR);
        int citizen = players.size() - role.size();
        for (int i = 0; i < citizen; i++) {
            role.add(Role.CITIZEN);
        }
        Collections.shuffle(role);
    }

    public void vote(Long playerNo, Integer targetNo) {
        votes.put(playerNo, targetNo);
    }

    public Integer voteResult() {
        int vote_cnt = votes.size();
        long live = players.values().stream()
            .filter(player -> !player.isDead()) // 살아있는 플레이어 수 계산
            .count();

        // 투표하지 않은 플레이어의 투표를 -1로 처리
        for (int i = 0; i < live - vote_cnt; i++) {
            votes.put(0L, -1);
        }

        // 투표 결과 집계 (누가 몇 표 받았는지)
        Map<Integer, Long> result = votes.values().stream()
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        // 가장 많이 득표한 플레이어 찾기
        Optional<Long> maxVotes = result.values().stream().max(Long::compare);

        if (maxVotes.isPresent()) {
            // 최다 득표를 받은 모든 플레이어 찾기
            List<Integer> topPlayers = result.entrySet().stream()
                .filter(entry -> entry.getValue().equals(maxVotes.get()))
                .map(Map.Entry::getKey)
                .toList();

            // 동점이면 -1 반환
            return (topPlayers.size() > 1) ? -1 : topPlayers.get(0);
        }

        return -1; // 투표가 없으면 -1 반환
    }


    public void finalVote() {
        final_vote++;
    }

    public boolean finalvoteResult() {
        long live = players.values().stream()
            .filter(player -> !player.isDead())
            .count();

        int target = voteResult();
        if (final_vote > (live / 2) && target != -1) {
            killTarget.put("VOTE", target);
            return true;
        }
        return false;
    }

    private void Kill(Long targetNo) {
        Player p = players.get(targetNo);
        p.setDead(true);
        p.updateSubscriptionsOnDeath(gameId);
        log.info(targetNo + "플레이어 사망 처리");
    }

    public List<Integer> killProcess() { // 밤중 킬
        if (killTarget.isEmpty()) {
            return null; // 죽일 대상이 없으면 바로 종료
        }

        // 치료된 플레이어 제외
        List<Integer> finalDeathList = new ArrayList<>(killTarget.values());
        if (healTarget != null) {
            finalDeathList.remove(healTarget);
        }

        // 실제 킬 처리
        for (Integer target : finalDeathList) {
            Kill(map_players.get(target));
        }
        log.warn("Final Kill List: " + finalDeathList);
        isGameOver();
        return finalDeathList;
    }

    public int heal(Integer targetNo) {
        healTarget = targetNo;
        int cnt = setting.getDoctorSkillUsage();
        if (cnt > 0) {
            setting.setDoctorSkillUsage(cnt - 1);
        }
        return setting.getDoctorSkillUsage();
    }

    public void specifyTarget(Role role, Integer targetNo) {
        killTarget.put(role.toString(), targetNo);
    }

    public Role findRole(Integer targetNo) {
        Role find = players.get(map_players.get(targetNo)).getRole();
        if (find == Role.ZOMBIE) {
            return Role.ZOMBIE;
        } else {
            return Role.CITIZEN;
        }
    }

    private void isGameOver() {
        //각 역할별 생존자 수 계산
        long citizen = players.values().stream()
            .filter(player -> player.getRole() != Role.ZOMBIE && player.getRole() != Role.MUTANT && !player.isDead())
            .count();
        long zombie = players.values().stream()
            .filter(player -> player.getRole() == Role.ZOMBIE && !player.isDead())
            .count();
        long mutant = players.values().stream()
            .filter(player -> player.getRole() == Role.MUTANT && !player.isDead())
            .count();

        if (zombie == 0 && mutant == 0) {
            this.gameStatus = GameStatus.CITIZEN_WIN;
        } else if (mutant == 0 && zombie >= citizen) {
            this.gameStatus = GameStatus.ZOMBIE_WIN;
        } else if (mutant > 0 && citizen + zombie <= mutant) {
            this.gameStatus = GameStatus.MUTANT_WIN;
        } else {
            log.info("[Game{}] Game is still in progress", gameId);
        }

        if (this.gameStatus != GameStatus.PLAYING) {
            log.info("[Game{}] Game over with status: {}", gameId, this.gameStatus);
        }
    }

    public void updateVoicePermissions(String phase) {
        players.forEach((playerNo, player) -> {
            if (player.isDead()) {
                player.setMuteMic(true);
                player.setMuteAudio(false); // 죽은 플레이어는 듣기만 가능
            } else if (phase.equals("day")) {
                // 낮 토론 시간 -> 모든 생존자 마이크+오디오 허용
                player.setMuteMic(false);
                player.setMuteAudio(false);
            } else {
                // 밤 -> 좀비만 말하기+듣기 가능, 나머지는 둘 다 음소거
                if (player.getRole() == Role.ZOMBIE) {
                    player.setMuteMic(false);
                    player.setMuteAudio(false);
                } else {
                    player.setMuteMic(true);
                    player.setMuteAudio(true); // 살아있는 시민 & 경찰 & 의사는 둘 다 음소거
                }
            }
        });
    }
}
