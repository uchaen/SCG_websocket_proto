package com.ycy.websocket;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.client.ReactorNettyWebSocketClient;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketMessage;
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy;
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService;
import java.net.URI;
import org.springframework.http.HttpHeaders;

@Component // Spring Bean으로 등록
public class WebSocketGlobalFilter implements GlobalFilter, Ordered {

    @Autowired
    private WebSocketSessionManager sessionManager;

    /*
     * WebSocketClient 주요 기능:
     * - 타겟 서버로 WebSocket 연결 수립
     * - 메시지 송수신을 위한 비동기 스트림 처리
     * - 연결 상태 관리 (연결, 종료, 에러 처리)
     * - client.execute()를 통해 실제 WebSocket 연결을 수행하고 메시지를 주고받음
     */
    private final WebSocketClient client = new ReactorNettyWebSocketClient();

    /*
     * HandshakeWebSocketService 주요 기능:
     * - HTTP 업그레이드 요청을 WebSocket 연결로 변환
     * - WebSocket 프로토콜에 필요한 핸드쉐이크 절차 수행:
     * 1. 클라이언트의 업그레이드 요청 검증
     * 2. WebSocket 키 검증
     * 3. 프로토콜 버전 확인
     * 4. 연결 승인 응답 생성
     * - ReactorNettyRequestUpgradeStrategy를 사용하여 Netty 기반의 비동기 업그레이드 처리
     */
    private final HandshakeWebSocketService webSocketService = new HandshakeWebSocketService(
            new ReactorNettyRequestUpgradeStrategy());

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {

        String path = exchange.getRequest().getURI().getPath();

        HttpHeaders headers = exchange.getRequest().getHeaders();

        // WebScoket 연결을 시작할때, 아래 헤더로 업그레이드를 요청하여 WebSocket Handshake를 시도함
        /*
         * GET /ws HTTP/1.1
         * Host: localhost:8080
         * Upgrade: websocket
         * Connection: Upgrade
         * Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==
         * Sec-WebSocket-Version: 13
         */

        // WebScoket 업그레이드 요청이면
        if (headers.getUpgrade().equalsIgnoreCase("websocket") && headers.getConnection().contains("Upgrade")) {
            System.out.println("[WebSocketGlobalFilter] WebSocket 연결 시도: " + path);
            ServerWebExchangeUtils.setAlreadyRouted(exchange); // 요청이 이미 라우팅되었음을 표시하는 플래그

            return handleWebSocket(exchange) // 클라이언트와 서버 사이의 WebSocket 프록시 수행 및 양방향 메시지 전달과 연결 상태 관리
                    .then(chain.filter(exchange)) // 다음 필터로 전달
                    .doOnError(error -> {
                        System.out.println("[WebSocketGlobalFilter] 라우팅 에러 발생: " + error.getMessage());
                    });
        }

        return chain.filter(exchange); // WebSocket 요청이 아니면 다음 필터로 전달
    }

    private Mono<Void> handleWebSocket(ServerWebExchange exchange) {

        // 지연 실행(lazy execution) - WebSocketHandler: WebSocket 연결이 수립된 후 세션을 처리하는 핸들러
        WebSocketHandler proxyHandler = clientSession -> {
            // clientSession: 클라이언트와의 WebSocket 연결 세션

            // 현재 요청에 매칭된 라우트 정보 가져오기
            Route route = exchange.getAttribute(ServerWebExchangeUtils.GATEWAY_ROUTE_ATTR);
            URI targetUri = route.getUri();

            System.out.println("[WebSocketGlobalFilter] WebSocket 연결 성공 - 클라이언트: " + clientSession.getId() + ", 대상 서버: " + targetUri);

            return client.execute(targetUri, serverSession -> { // WebSocketClient를 사용하여 타겟 서버와 WebSocket 연결
                // 세션 매니저에 세션 추가
                sessionManager.addSession(clientSession, serverSession);

                // 클라이언트 -> 서버 메시지 전달
                Mono<Void> clientToServer = clientSession.receive()
                        .map(WebSocketMessage::retain) // 메시지 수신 후 참조 카운트 증가 - 메시지를 다른 세션으로 전달하기 전에 메모리가 해제되는 손실을 방지
                        .doOnNext(message -> {
                            System.out.println("[WebSocketGlobalFilter] 클라이언트 -> 서버: " +  message.getPayloadAsText());
                        })
                        .flatMap(message -> serverSession.send(Mono.just(message))) // 타겟 서버로 메시지 전송
                        .then();

                // 서버 -> 클라이언트 메시지 전달
                Mono<Void> serverToClient = serverSession.receive()
                        .map(WebSocketMessage::retain) // 메시지 수신 후 참조 카운트 증가
                        .doOnNext(message -> {
                            System.out.println("[WebSocketGlobalFilter] 서버 -> 클라이언트: " + message.getPayloadAsText());
                        })
                        .flatMap(message -> clientSession.send(Mono.just(message))) // 클라이언트로 메시지 전송
                        .then();

                // 양방향 메시지 전달을 하나의 스트림으로 결합
                return Mono.zip(clientToServer, serverToClient)
                        .doOnError(error -> System.out.println("[WebSocketGlobalFilter] WebSocket 에러 발생: " + error.getMessage()))
                        .doFinally(signalType -> { // signalType: 웹소켓 연결 종료 시 발생하는 종료 이벤트 유형
                            System.out.println("[WebSocketGlobalFilter] WebSocket 연결 종료 - " + signalType + " : " + clientSession.getId());
                            sessionManager.removeSession(clientSession.getId());
                        })
                        .then();
            });
        };

        // WebSocket 핸드쉐이크 수행(=HTTP 요청을 WebSocket 연결로 업그레이드) 및 proxyHandler를 사용하여 연결 처리 시작
        return webSocketService.handleRequest(exchange, proxyHandler);
    }

    @Override
    public int getOrder() {
        return -2; // 필터 체인에서 높은 우선순위로 실행되도록 설정 (낮은 숫자가 높은 우선순위)
    }
}