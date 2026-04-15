package com.auraboot.framework.im.config;

import com.auraboot.framework.auth.util.JwtUtil;
import com.auraboot.framework.im.websocket.ImWebSocketHandler;
import com.auraboot.framework.user.dao.entity.User;
import com.auraboot.framework.user.service.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.Map;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private static final Logger log = LoggerFactory.getLogger(WebSocketConfig.class);

    private final ImWebSocketHandler imWebSocketHandler;
    private final JwtUtil jwtUtil;
    private final UserService userService;

    public WebSocketConfig(ImWebSocketHandler imWebSocketHandler, JwtUtil jwtUtil, UserService userService) {
        this.imWebSocketHandler = imWebSocketHandler;
        this.jwtUtil = jwtUtil;
        this.userService = userService;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(imWebSocketHandler, "/api/im/ws")
                .addInterceptors(new JwtHandshakeInterceptor())
                .setAllowedOrigins("*");
    }

    /**
     * Validates JWT token from query param during WebSocket handshake.
     * Sets userId and tenantId as session attributes for the handler.
     */
    private class JwtHandshakeInterceptor implements HandshakeInterceptor {

        @Override
        public boolean beforeHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                        WebSocketHandler wsHandler, Map<String, Object> attributes) {
            if (request instanceof ServletServerHttpRequest servletRequest) {
                String token = servletRequest.getServletRequest().getParameter("token");
                if (token == null || token.isBlank()) {
                    log.warn("IM WebSocket handshake rejected: missing token");
                    return false;
                }

                try {
                    String userPid = jwtUtil.extractIdentifier(token);
                    Long tenantId = jwtUtil.extractTenantId(token);

                    if (userPid == null || tenantId == null) {
                        log.warn("IM WebSocket handshake rejected: invalid token claims");
                        return false;
                    }

                    User user = userService.findByPid(userPid);
                    if (user == null) {
                        log.warn("IM WebSocket handshake rejected: user not found for pid={}", userPid);
                        return false;
                    }

                    attributes.put("userId", user.getId());
                    attributes.put("tenantId", tenantId);
                    return true;
                } catch (Exception e) {
                    log.warn("IM WebSocket handshake rejected: {}", e.getMessage());
                    return false;
                }
            }
            return false;
        }

        @Override
        public void afterHandshake(ServerHttpRequest request, ServerHttpResponse response,
                                    WebSocketHandler wsHandler, Exception exception) {
            // no-op
        }
    }
}
