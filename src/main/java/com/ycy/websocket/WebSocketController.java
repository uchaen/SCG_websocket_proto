package com.ycy.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/websocket")
public class WebSocketController {

    @Autowired
    private WebSocketSessionManager sessionManager;

    @DeleteMapping("/sessions/{sessionId}")
    public Mono<ResponseEntity<String>> closeSession(@PathVariable String sessionId) {
        return sessionManager.closeSession(sessionId)
                .then(Mono.just(ResponseEntity.ok("세션이 성공적으로 종료되었습니다: " + sessionId)))
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().body("세션 종료 중 오류 발생: " + e.getMessage())));
    }

    @GetMapping("/sessions")
    public ResponseEntity<List<String>> getActiveSessions() {
        List<String> sessions = sessionManager.getClientSessions().keySet()
                .stream()
                .collect(Collectors.toList());
        return ResponseEntity.ok(sessions);
    }
}