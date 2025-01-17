package com.ycy.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {
    private final Map<String, WebSocketSession> sessionMap = new ConcurrentHashMap<>();

    public void addSession(WebSocketSession clientSession, WebSocketSession serverSession) {
        System.out.println("[WebSocketSessionManager] 클라이언트 세션 ID: " + clientSession.getId());
        System.out.println("[WebSocketSessionManager] 서버 세션 ID: " + serverSession.getId());
        sessionMap.put(clientSession.getId(), serverSession);
    }

    public WebSocketSession removeSession(String sessionId) {
        System.out.println("[WebSocketSessionManager] 세션 제거 - 세션 ID: " + sessionId);
        return sessionMap.remove(sessionId);
    }

    // 세션 조회 API 호출 시 호출되는 메서드
    public Map<String, WebSocketSession> getSessionMap() {
        return sessionMap;
    }

    // 세션 종료 API 호출 시 호출되는 메서드
    public Mono<Void> closeSession(String sessionId) {
        System.out.println("[WebSocketSessionManager] 세션 종료 API 감지 - 세션 ID: " + sessionId);
        WebSocketSession serverSession = sessionMap.get(sessionId);
        if (serverSession != null) {
            return serverSession.close(); // WebSocket 프로토콜의 특성 상, 한쪽이 close되면 다른 쪽도 자동으로 close 됨
        }
        return Mono.error(new RuntimeException("해당 세션을 찾을 수 없습니다. 세션 ID: " + sessionId));
    }
}