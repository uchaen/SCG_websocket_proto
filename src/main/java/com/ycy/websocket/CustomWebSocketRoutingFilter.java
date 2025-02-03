package com.ycy.websocket;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.net.URI;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cloud.gateway.filter.WebsocketRoutingFilter;
import org.springframework.cloud.gateway.filter.headers.HttpHeadersFilter;
import org.springframework.cloud.gateway.support.ServerWebExchangeUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.socket.client.WebSocketClient;
import org.springframework.web.reactive.socket.server.WebSocketService;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.reactive.socket.WebSocketHandler;
import org.springframework.web.reactive.socket.WebSocketSession;
import org.springframework.web.reactive.socket.CloseStatus;
import org.springframework.http.HttpHeaders;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.reactivestreams.Publisher;

@Component
public class CustomWebSocketRoutingFilter extends WebsocketRoutingFilter {

    @Autowired
    private WebSocketSessionManager sessionManager;

    private final WebSocketClient webSocketClient;
    private final WebSocketService webSocketService;
    private final ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider;

    private volatile List<HttpHeadersFilter> headersFilters;

    public CustomWebSocketRoutingFilter(WebSocketClient webSocketClient, WebSocketService webSocketService,
            ObjectProvider<List<HttpHeadersFilter>> headersFiltersProvider) {
        super(webSocketClient, webSocketService, headersFiltersProvider);
        this.webSocketClient = webSocketClient;
        this.webSocketService = webSocketService;
        this.headersFiltersProvider = headersFiltersProvider;
    }

    // HTTP(S) 요청을 WebSocket 프로토콜로 업그레이드할 때, 스킴을 http > ws, https > wss 변경해야 함
    static String convertHttpToWs(String scheme) {
        scheme = scheme.toLowerCase();
        return "http".equals(scheme) ? "ws" : ("https".equals(scheme) ? "wss" : scheme);
    }

    public int getOrder() {
        // 기존 order = 2147483646
        return 2147483646 - 1;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        // WebSocket 업그레이드 요청일 경우 스킴을 변경
        changeSchemeIfIsWebSocketUpgrade(exchange);

        URI requestUrl = (URI) exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        String scheme = requestUrl.getScheme();

        // 이미 라우팅된 요청이 아니고, 스킴이 ws 또는 wss인 경우
        if (!ServerWebExchangeUtils.isAlreadyRouted(exchange) && ("ws".equals(scheme) || "wss".equals(scheme))) {
            // 요청을 라우팅된 것으로 플래그 설정
            ServerWebExchangeUtils.setAlreadyRouted(exchange);

            HttpHeaders headers = exchange.getRequest().getHeaders();

            // WebSocket 요청에 적합하게 바꿔주는 헤더 필터를 적용
            HttpHeaders filtered = HttpHeadersFilter.filterRequest(this.getHeadersFilters(), exchange);

            // Sec-WebSocket-Protocol 헤더에 들어있는 서브 프로토콜 리스트를 가져옴
            // 서브 프로토콜 리스트 = 클라이언트와 서버가 사용할 프로토콜 리스트(예: JSON-RPC, STOMP, MQTT 등)
            List<String> protocols = this.getProtocols(headers);
            System.out.println("[CustomWebSocketRoutingFilter] protocols:[" + protocols + "]");

            // WebSocket 핸드쉐이크 수행(=HTTP 요청을 WebSocket 연결로 업그레이드) 및 Handler를 사용하여 연결 처리 시작
            return this.webSocketService.handleRequest(exchange,
                    new ProxyWebSocketHandler(requestUrl, this.webSocketClient, filtered, protocols,
                            this.sessionManager));
        } else {
            // 일반적인 필터 체인을 계속 진행
            return chain.filter(exchange);
        }
    }

    // Sec-WebSocket-Protocol 헤더에 들어있는 서브 프로토콜 리스트를 가져옴
    List<String> getProtocols(HttpHeaders headers) {
        List<String> protocols = headers.get("Sec-WebSocket-Protocol");
        if (protocols != null) {
            ArrayList<String> updatedProtocols = new ArrayList();

            for (int i = 0; i < ((List) protocols).size(); ++i) {
                String protocol = (String) ((List) protocols).get(i);
                updatedProtocols.addAll(Arrays.asList(StringUtils.tokenizeToStringArray(protocol, ",")));
            }

            protocols = updatedProtocols;
        }

        return (List) protocols;
    }

    // HTTP 요청 헤더를 필터링하는 데 사용되는 헤더 필터 리스트를 가져옵니다.
    List<HttpHeadersFilter> getHeadersFilters() {
        if (this.headersFilters == null) {
            this.headersFilters = (List) this.headersFiltersProvider.getIfAvailable(ArrayList::new);
            this.headersFilters.add((headers, exchange) -> {
                HttpHeaders filtered = new HttpHeaders();
                filtered.addAll(headers);
                filtered.remove("Host");
                boolean preserveHost = (Boolean) exchange
                        .getAttributeOrDefault(ServerWebExchangeUtils.PRESERVE_HOST_HEADER_ATTRIBUTE, false);
                if (preserveHost) {
                    String host = exchange.getRequest().getHeaders().getFirst("Host");
                    filtered.add("Host", host);
                }

                return filtered;
            });
            this.headersFilters.add((headers, exchange) -> {
                HttpHeaders filtered = new HttpHeaders();
                Iterator var3 = headers.entrySet().iterator();

                while (var3.hasNext()) {
                    Map.Entry<String, List<String>> entry = (Map.Entry) var3.next();
                    if (!((String) entry.getKey()).toLowerCase().startsWith("sec-websocket")) {
                        filtered.addAll((String) entry.getKey(), (List) entry.getValue());
                    }
                }

                return filtered;
            });
        }

        return this.headersFilters;
    }

    // WebSocket 업그레이드 요청일 경우 URL의 스킴을 http에서 ws로, https에서 wss로 변경
    static void changeSchemeIfIsWebSocketUpgrade(ServerWebExchange exchange) {
        URI requestUrl = (URI) exchange.getRequiredAttribute(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR);
        String scheme = requestUrl.getScheme().toLowerCase();
        String upgrade = exchange.getRequest().getHeaders().getUpgrade();
        System.out.println("[CustomWebSocketRoutingFilter] upgrade:[" + upgrade + "]" + " scheme:[" + scheme + "]");

        if ("WebSocket".equalsIgnoreCase(upgrade) && ("http".equals(scheme) || "https".equals(scheme))) {
            String wsScheme = convertHttpToWs(scheme);
            boolean encoded = ServerWebExchangeUtils.containsEncodedParts(requestUrl);
            URI wsRequestUrl = UriComponentsBuilder.fromUri(requestUrl).scheme(wsScheme).build(encoded).toUri();
            exchange.getAttributes().put(ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR, wsRequestUrl);
            System.out.println("changeSchemeTo:[" + wsRequestUrl + "]");
        }
    }

    private static class ProxyWebSocketHandler implements WebSocketHandler {

        private final WebSocketSessionManager sessionManager;
        private final WebSocketClient client;
        private final URI url;
        private final HttpHeaders headers;
        private final List<String> subProtocols;

        ProxyWebSocketHandler(URI url, WebSocketClient client, HttpHeaders headers, List<String> protocols,
                WebSocketSessionManager sessionManager) {
            this.client = client;
            this.url = url;
            this.headers = headers;
            this.sessionManager = sessionManager;
            if (protocols != null) {
                this.subProtocols = protocols;
            } else {
                this.subProtocols = Collections.emptyList();
            }

        }

        public List<String> getSubProtocols() {
            return this.subProtocols;
        }

        // WebSocket 세션을 처리합니다.
        public Mono<Void> handle(final WebSocketSession session) {
            System.out.println("[CustomWebSocketRoutingFilter] 연결 시작 : " + session.getId());

            // 클라이언트와 서버 간의 통신을 처리하는 프록시 세션을 생성
            return this.client.execute(this.url, this.headers, new WebSocketHandler() {

                // 종료 코드에 따라 적절한 클로즈 상태 반환
                private CloseStatus adaptCloseStatus(CloseStatus closeStatus) {
                    int code = closeStatus.getCode();
                    if (code > 2999 && code < 5000) {
                        // 사용자 정의 코드로 간주
                        return closeStatus;
                    } else {
                        switch (code) {
                            case 1000: // 정상 종료
                            case 1001: // 종료
                            case 1002: // 프로토콜 오류
                            case 1003: // 데이터형식 오류
                            case 1007: // 데이터형식 오류
                            case 1008: // 정책 위반
                            case 1009: // 메시지가 너무 큼
                            case 1010: // 확정 필요
                            case 1011: // 서버오류
                                return closeStatus;
                            case 1004:
                            case 1005:
                            case 1006:
                            case 1012:
                            case 1013:
                            case 1014:
                            case 1015:
                            default:
                                return CloseStatus.PROTOCOL_ERROR;
                        }
                    }
                }

                // 프록시 세션을 처리합니다.
                public Mono<Void> handle(WebSocketSession proxySession) {
                    System.out.println("[CustomWebSocketRoutingFilter] 프록시 세션 시작 : " + proxySession.getId());

                    sessionManager.addSession(session, proxySession);

                    Mono<Void> serverClose = session.closeStatus().filter((__) -> {
                        return proxySession.isOpen();
                    }).map(this::adaptCloseStatus).flatMap(closeStatus -> {
                        return proxySession.close(closeStatus);
                    });
                    Mono<Void> proxyClose = proxySession.closeStatus().filter((__) -> {
                        return session.isOpen();
                    }).flatMap(closeStatus -> {
                        CloseStatus adaptedStatus = adaptCloseStatus(closeStatus);
                        return proxySession.close(adaptedStatus);
                    });

                    Mono<Void> proxySessionSend = proxySession
                            .send(session.receive().doOnNext(message -> {
                                System.out.println(
                                        "[CustomWebSocketRoutingFilter] 클라이언트 -> 서버 : " + message.getPayloadAsText());
                                message.retain();
                            }));
                    Mono<Void> serverSessionSend = session
                            .send(proxySession.receive().doOnNext(message -> {
                                System.out.println(
                                        "[CustomWebSocketRoutingFilter] 서버 -> 클라이언트 : " + message.getPayloadAsText());
                                message.retain();
                            }));
                    // 메시지 변조 예시 코드
                    // Mono<Void> serverSessionSend = session
                    // .send(proxySession.receive().map(message -> {
                    // // 메시지 변조: 내용을 대문자로 변환
                    // String originalPayload = message.getPayloadAsText();
                    // String modifiedPayload = originalPayload.toUpperCase();
                    // System.out.println("[CustomWebSocketRoutingFilter] 서버 -> 클라이언트 (변조됨) : " +
                    // modifiedPayload);
                    //
                    // // 변조된 메시지를 새로운 텍스트 메시지로 변환
                    // return session.textMessage(modifiedPayload);
                    // }));

                    Mono.when(new Publisher[] { serverClose, proxyClose }).doFinally(signal -> {
                        System.out.println("[CustomWebSocketRoutingFilter] 연결 종료: " + session.getId());
                        sessionManager.removeSession(session.getId());
                    }).subscribe();

                    return Mono.zip(proxySessionSend, serverSessionSend).then();
                }

                // 서브 프로토콜 리스트를 반환합니다.
                public List<String> getSubProtocols() {
                    return ProxyWebSocketHandler.this.subProtocols;
                }
            });
        }
    }
}