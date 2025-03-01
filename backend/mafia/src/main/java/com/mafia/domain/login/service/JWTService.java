package com.mafia.domain.login.service;

import static com.mafia.global.common.model.dto.BaseResponseStatus.INVALID_REFRESH_TOKEN;
import static com.mafia.global.common.model.dto.BaseResponseStatus.MEMBER_NOT_FOUND;
import static com.mafia.global.common.model.dto.BaseResponseStatus.REFRESH_TOKEN_EXPIRED;
import static com.mafia.global.common.model.dto.BaseResponseStatus.REFRESH_TOKEN_NOT_FOUND;

import com.mafia.domain.login.model.dto.ReissueDto;
import com.mafia.domain.member.model.entity.Member;
import com.mafia.domain.member.repository.MemberRepository;
import com.mafia.global.common.exception.exception.BusinessException;
import com.mafia.global.common.service.RedisService;
import com.mafia.global.common.utils.JWTUtil;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class JWTService {

    private final JWTUtil jwtUtil;
    private final RedisService redisService;
    private final MemberRepository memberRepository;

    public ReissueDto reissueTokens(String oldRefresh) {
        Member member = validateRefreshToken(oldRefresh);

        String newAccess = jwtUtil.createAccessToken(member.getProviderId(), member.getMemberId());
        String newRefresh = jwtUtil.createRefreshToken(member.getProviderId(),
            member.getMemberId());

        // 게스트와 일반 유저 구분
        if (member.getProviderId().startsWith("guest_")) {
            // 게스트 유저의 경우 60분 만료 (CustomSuccessHandler와 동일하게)
            redisService.saveWithExpiry(member.getProviderId(), newRefresh, 6, TimeUnit.HOURS);
        } else {
            // 일반 유저의 경우 7일 만료
            redisService.saveWithExpiry(member.getProviderId(), newRefresh, 7, TimeUnit.DAYS);
        }
        return ReissueDto.builder()
            .newAccessToken(newAccess)
            .newRefreshToken(newRefresh)
            .providerId(member.getProviderId())
            .build();
    }

    private Member validateRefreshToken(String oldRefresh) {
        if (oldRefresh == null) {
            throw new BusinessException(REFRESH_TOKEN_NOT_FOUND);
        }

        if (jwtUtil.isExpired(oldRefresh)) {
            throw new BusinessException(REFRESH_TOKEN_EXPIRED);
        }

        String providerId = jwtUtil.getProviderId(oldRefresh);
        Optional<String> savedRefresh = redisService.get(providerId, String.class);

        if (savedRefresh.isEmpty() || !oldRefresh.equals(savedRefresh.get())) {
            throw new BusinessException(INVALID_REFRESH_TOKEN);
        }

        return memberRepository.findByProviderId(providerId)
            .orElseThrow(() -> new BusinessException(MEMBER_NOT_FOUND));
    }
}