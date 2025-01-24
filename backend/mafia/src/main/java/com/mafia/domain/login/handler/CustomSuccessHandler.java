package com.mafia.domain.login.handler;

import com.mafia.domain.login.utils.AuthenticationUtil;
import com.mafia.domain.login.utils.CookieUtil;
import com.mafia.domain.login.utils.JWTUtil;
import com.mafia.global.common.service.RedisService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;

@Slf4j
@Component
@RequiredArgsConstructor
public class CustomSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

    private final AuthenticationUtil authenticationUtil;
    private final JWTUtil jwtUtil;
    private final CookieUtil cookieUtil;
    private final RedisService redisService;

    @Value("${app.baseUrl}")
    private String baseUrl;
    private static final String LOGIN_SUCCESS_URI = "/api/login/success";

    @Override
    @Transactional
    public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response, Authentication authentication) throws IOException {

        String providerId = authenticationUtil.getProviderId();
        Long memberId = authenticationUtil.getMemberId();

        // 토큰 생성
        String access = jwtUtil.createAccessToken(providerId,memberId);
        String refresh = jwtUtil.createRefreshToken(providerId, memberId);

        // Refresh 토큰 저장
        redisService.save(providerId, refresh);

        // 응답 설정
        response.addCookie(cookieUtil.createCookie("ACCESS", access));
        response.addCookie(cookieUtil.createCookie("REFRESH", refresh));
        response.sendRedirect(baseUrl + LOGIN_SUCCESS_URI);
    }
}