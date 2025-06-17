package com.mafia.domain.chat.model;

import java.security.Principal;
import lombok.Getter;

// STOMP에서 사용할 단순한 Principal 구현체
@Getter
public class StompPrincipal implements Principal {

    private final Long memberId;

    public StompPrincipal(Long memberId) {
        this.memberId = memberId;
    }

    @Override
    public String getName() {
        // getName()을 사용자 식별용으로 사용
        return String.valueOf(memberId);
    }

}
