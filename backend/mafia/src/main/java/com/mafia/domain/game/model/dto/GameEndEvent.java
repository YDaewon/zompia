package com.mafia.domain.game.model.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public class GameEndEvent {
    private final Long gameId;
}
