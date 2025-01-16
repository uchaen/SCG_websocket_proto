package com.ycy.websocket;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter;
import org.springframework.beans.factory.annotation.Autowired;
import java.util.HashMap;
import java.util.Map;

@Configuration
public class WebSocketConfig {

    @Autowired
    private ReactiveWebSocketHandler webSocketHandler;

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        // /ws 경로로 들어오는 웹소켓 연결 요청을 ReactiveWebSocketHandler가 처리하도록
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/ws", webSocketHandler); // 주입받은 핸들러 사용

        // HandlerMapping은 들어오는 웹소켓 요청을 적절한 핸들러로 연결해주는 역할을 함
        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setUrlMap(map); // URL과 핸들러의 매핑 정보를 설정
        handlerMapping.setOrder(-1); // 핸들러 매핑의 우선 순위를 설정

        return handlerMapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        // WebSocketHandlerAdapter는 웹소켓 통신에 필요한 기본 기능을 제공
        return new WebSocketHandlerAdapter();
    }
}
