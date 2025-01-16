package com.ycy.websocket;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.socket.WebSocketSession;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class WebSocketSessionManager {
    private final Map<String, WebSocketSession> clientSessions = new ConcurrentHashMap<>();
    private final Map<String, WebSocketSession> serverSessions = new ConcurrentHashMap<>();

    public void addSession(String sessionId, WebSocketSession clientSession, WebSocketSession serverSession) {
        System.out.println("세션 추가 - 클라이언트 세션 ID: " + clientSession.getId());
        System.out.println("세션 추가 - 서버 세션 ID: " + serverSession.getId());
        clientSessions.put(sessionId, clientSession);
        serverSessions.put(sessionId, serverSession);
    }

    public Mono<Void> closeSession(String sessionId) {
        System.out.println("세션 종료 - 클라이언트 세션 ID: " + sessionId);
        WebSocketSession clientSession = clientSessions.remove(sessionId);
        WebSocketSession serverSession = serverSessions.remove(sessionId);

        if (clientSession != null && serverSession != null) {
            return Mono.zip(
                    clientSession.close(),
                    serverSession.close()).then();
        }
        return Mono.empty();
    }

    public void removeSession(String sessionId) {
        System.out.println("세션 제거 - 클라이언트 세션 ID: " + sessionId);
        clientSessions.remove(sessionId);
        serverSessions.remove(sessionId);
    }

    public Map<String, WebSocketSession> getClientSessions() {
        return clientSessions;
    }

    public Map<String, WebSocketSession> getServerSessions() {
        return serverSessions;
    }
}