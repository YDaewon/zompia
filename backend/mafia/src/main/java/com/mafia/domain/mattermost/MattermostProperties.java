package com.mafia.domain.mattermost;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Getter
@Setter
@ConfigurationProperties("notification.mattermost")
public class MattermostProperties {

    private boolean mmEnabled = true;
    private String webhookUrl;
    private String channel;
    private String pretext;
    private String color = "#ff5d52";
    private String authorName = "server";
    private String title;
    private String text = "";
    private String footer = LocalDateTime.now()
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
}