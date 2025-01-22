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
    // 웹소켓 요청임을 확인 & 핸드쉐이크는 spring webflux 내부 매커니즘에 의해 자동 처리

    @Autowired
    private ReactiveWebSocketHandler webSocketHandler;

    @Bean
    public HandlerMapping webSocketHandlerMapping() {
        // /ws 경로로 들어오는 웹소켓 연결 요청을 ReactiveWebSocketHandler가 처리하도록 매핑
        Map<String, WebSocketHandler> map = new HashMap<>();
        map.put("/ws", webSocketHandler); // 주입받은 핸들러 사용

        // SimpleUrlHandlerMapping : 들어오는 웹소켓 요청을 적절한 핸들러로 연결해주는 역할
        SimpleUrlHandlerMapping handlerMapping = new SimpleUrlHandlerMapping();
        handlerMapping.setUrlMap(map); // URL과 핸들러의 매핑 정보를 설정
        handlerMapping.setOrder(-1000); // 핸들러 매핑의 우선 순위를 설정 - 숫자가 작을수록 높은 우선순위

        return handlerMapping;
    }

    @Bean
    public WebSocketHandlerAdapter handlerAdapter() {
        // WebSocketHandlerAdapter : 웹소켓 요청을 처리할 수 있도록 Spring WebFlux 인프라에 WebSocket 기능 추가
        return new WebSocketHandlerAdapter();
    }
}
