package com.mafia.domain.game.model.game;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.mafia.domain.room.model.redis.Participant;
import io.swagger.v3.oas.annotations.media.Schema;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
@JsonIgnoreProperties(ignoreUnknown = true)
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
    private STATUS status;

    @Schema(description = "현재 라운드에서 의사가 치료 대상으로 지정한 플레이어의 ID", example = "101")
    private Integer healTarget;

    @Schema(description = "현재 라운드에서 공격 대상으로 지정한 플레이어의 ID", example = "102")
    private int killTarget;

    @Schema(description = "게임 옵션")
    private GameOption setting;

    // @PostConstruct <- 이후 알아봄
    public Game(long roomId, GameOption setting) {
        this.gameId = roomId;
        this.players = new HashMap<>();
        this.votes = new HashMap<>();
        this.map_players=new HashMap<>();
        this.healTarget = null;
        this.killTarget = 0;
        this.setting = setting; // <- POST CONSTRUCT
    }

    /*
     * 플레이어 추가
     * - 게임에 참가할 플레이어를 추가한다.
     * */
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
        map_players.put(players.size(), participant.getMemberId());
    }

    public void startGame() {
        this.status = STATUS.PLAYING;
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
            }

            if (userRole == Role.MUTANT) {
                player.setEnableVote(false);
            }
            rcnt++;
        }
        log.info("[Game{}] Role distribution is completed", gameId);
    }

    public void init_role(List<Role> role) {
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
        if(players.get(map_players.get(targetNo)).isDead()) votes.put(playerNo, -1);
        votes.put(playerNo, targetNo);
    }

    public Integer voteResult() {
        Map<Integer, Long> result = votes.values().stream()
            .collect(Collectors.groupingBy(Function.identity(), Collectors.counting()));

        return result.entrySet().stream()
            .max(Map.Entry.comparingByValue())
            .map(Map.Entry::getKey)
            .orElse(-1);
    }

    public void finalVote(){
        final_vote++;
    }

    public boolean finalvoteResult() {
        long live = players.values().stream()
            .filter(player -> !player.isDead())
            .count();

        int target = voteResult();
        if(final_vote > (live / 2) && target != -1){
            killTarget |= 1 <<(target - 1);
            return true;
        }
        return false;
    }

    private void Kill(Long targetNo) {
        Player p = players.get(targetNo);
        p.setDead(true);
        p.updateSubscriptionsOnDeath(gameId);
        isGameOver();
    }

    public List<Integer> processRoundResults() { // 밤중 킬
        if (killTarget == 0) {
            healTarget = null;
            return null; // 죽일 대상이 없으면 바로 종료
        }

        // 치료된 플레이어 제외
        List<Integer> finalDeathList = new ArrayList<>();
        for (int i = 0; i < players.size(); i++){
            if ((killTarget & (1 << i)) != 0)
                finalDeathList.add(i+1);
        }

        if (healTarget != null && finalDeathList.contains(healTarget)) {
            finalDeathList.remove(healTarget);
        }

        // 실제 킬 처리
        for (Integer target : finalDeathList) {
            Kill(map_players.get(target));

        }

        // 라운드가 끝나면 리스트 초기화
        healTarget = null;
        killTarget = 0;

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

    public void setKillTarget(Integer targetNo) {
        killTarget |= (1 << targetNo);
    }

    public Role findRole(Long playerNo, Integer targetNo) {
        Role find = players.get(map_players.get(targetNo)).getRole();
        if (find == Role.ZOMBIE) {
            players.get(playerNo).setEnableVote(false);
            return Role.ZOMBIE;
        } else {
            return Role.CITIZEN;
        }
    }

    private void isGameOver() {
        //각 역할별 생존자 수 계산
        long citizen = players.values().stream()
            .filter(player -> player.getRole() == Role.CITIZEN && !player.isDead())
            .count();
        long zombie = players.values().stream()
            .filter(player -> player.getRole() == Role.ZOMBIE && !player.isDead())
            .count();
        long mutant = players.values().stream()
            .filter(player -> player.getRole() == Role.MUTANT && !player.isDead())
            .count();

        if (zombie == 0 && mutant == 0) {
            this.status = STATUS.CITIZEN_WIN;
        } else if (mutant == 0 && zombie >= citizen) {
            this.status = STATUS.ZOMBIE_WIN;
        } else if (mutant > 0 && citizen + zombie <= mutant) {
            this.status = STATUS.MUTANT_WIN;
        } else {
            log.info("[Game{}] Game is still in progress", gameId);
        }

        if (this.status != STATUS.PLAYING) {
            log.info("[Game{}] Game over with status: {}", gameId, this.status);
        }
    }

    /**
     * 페이즈별 음성 채팅 권한 관리
     */
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
