package com.ycy.websocket;

import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import reactor.core.publisher.Mono;
import java.net.URI;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class ReactiveWebSocketHandler implements WebSocketHandler {
    // 이 핸들러는 프록시 역할을 하며, 두 개의 웹소켓 연결을 관리합니다
    // 클라이언트 <-> 게이트웨이(8080 포트) <-> 실제 웹소켓 서버(7777 포트)
    private final WebSocketClient client = new ReactorNettyWebSocketClient();
    private final URI serverUri = URI.create("ws://localhost:7777");

    @Autowired
    private WebSocketSessionManager sessionManager;

    @Override
    public Mono<Void> handle(WebSocketSession clientSession) {

        // 클라이언트 연결 시작 로그
        System.out.println("[핸들러] 새로운 클라이언트 연결 시작 - 세션 ID: " + clientSession.getId());

        return client.execute(serverUri, serverSession -> {
            // 서버와의 연결 성공 로그
            System.out.println("[핸들러] 웹소켓 서버(7777)와 연결 성공 - 클라이언트 세션 ID: " + clientSession.getId());

            // 세션 매니저에 세션 추가
            sessionManager.addSession(clientSession, serverSession);

            // 클라이언트 -> 서버
            Mono<Void> clientToServer = clientSession.receive()
                    .doOnNext(message -> {
                        System.out.println("[핸들러] Client -> Server: " + message.getPayloadAsText());
                    })
                    .map(msg -> serverSession.textMessage(msg.getPayloadAsText()))
                    .as(serverSession::send);

            // 서버 -> 클라이언트
            Mono<Void> serverToClient = serverSession.receive()
                    .doOnNext(message -> {
                        System.out.println("[핸들러] Server -> Client: " + message.getPayloadAsText());
                    })
                    .map(msg -> clientSession.textMessage(msg.getPayloadAsText()))
                    .as(clientSession::send);

            // 양방향 통신 실행
            return Mono.zip(clientToServer, serverToClient) // 두 개의 Mono를 합쳐서 하나의 Mono로 반환
                    .doOnSubscribe(sub -> System.out.println("[핸들러] 양방향 통신 시작 - 세션 ID: " + clientSession.getId()))
                    .doOnError(error -> {
                        System.err.println("[핸들러] 웹소켓 에러 발생 : " + error.getMessage());
                        error.printStackTrace();
                    })
                    .doFinally(signalType -> {
                        System.out.println("[핸들러] 웹소켓 연결 종료 - " + signalType + " : " + clientSession.getId());
                        sessionManager.removeSession(clientSession.getId());
                    })
                    .then();
        })
        .onErrorResume(error -> {
            System.err.println("[핸들러] 서버 연결 중 에러 발생: " + error.getMessage());
            error.printStackTrace();
            return Mono.empty();
        });
    }
}