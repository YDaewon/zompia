package com.mafia.domain.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class GameStartEvent {
    private final Long gameId;
}
