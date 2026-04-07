package com.chat.p2p.config;

import com.chat.p2p.model.P2PMessage;
import com.chat.p2p.service.P2PNetworkService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Обработчик WebSocket соединений.
 * 
 * Зачем WebSocket? HTTP polling - это как звонить другу каждые 5 секунд
 * и спрашивать "ну что, есть сообщения?". WebSocket - это открытая линия.
 * Сидим на трубке, ждём когда зазвонит.
 * 
 * Наследуем TextWebSocketHandler - работаем только с текстом.
 * Бинарные данные по P2P ходят напрямую, тут только управление и уведомления.
 */
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(ChatWebSocketHandler.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();
    
    private final P2PNetworkService networkService;
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>(); // Все активные WebSocket сессии

    public ChatWebSocketHandler(P2PNetworkService networkService) {
        this.networkService = networkService;
        
        // Подписываемся на события P2P сети - получаем уведомления
        networkService.addListener(new P2PNetworkService.NetworkListener() {
            @Override
            public void onMessageReceived(P2PMessage message) {
                broadcastMessage(message);
            }

            @Override
            public void onMessageDelivered(String messageId) {
                broadcastDelivery(messageId);
            }

            @Override
            public void onTyping(String peerId) {
                broadcastTyping(peerId);
            }

            @Override
            public void onTypingStopped(String peerId) {
                broadcastTypingStopped(peerId);
            }

            @Override
            public void onAuthRequest(String peerId, String token, String secret) {
                broadcastAuthRequest(peerId, token);
            }

            @Override
            public void onAuthResponse(String peerId, String token, boolean approved) {
                broadcastAuthResponse(peerId, token, approved);
            }

            @Override
            public void onPeerStatusChanged(String peerId, String status, String statusMessage) {
                broadcastStatus(peerId, status, statusMessage);
            }

            @Override
            public void onMessageRead(String messageId, String readerId) {
                broadcastMessageRead(messageId, readerId);
            }

            @Override
            public void onMessageEdited(String messageId) {
                broadcastMessageEdited(messageId);
            }

            @Override
            public void onMessageDeleted(String messageId) {
                broadcastMessageDeleted(messageId);
            }

            @Override
            public void onReactionAdded(String messageId, String peerId, String emoji) {
                broadcastReaction(messageId, peerId, emoji);
            }
        });
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);
        log.info("WebSocket connected: {}", session.getId());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        log.info("WebSocket disconnected: {}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        try {
            P2PMessage msg = objectMapper.readValue(message.getPayload(), P2PMessage.class);
            
            if ("TYPING".equals(msg.getType())) {
                networkService.sendTyping(msg.getTargetId());
                return;
            }
            
            if ("TYPING_STOPPED".equals(msg.getType())) {
                networkService.sendTypingStopped(msg.getTargetId());
                return;
            }
            
        } catch (Exception e) {
            log.error("Error handling message: {}", e.getMessage());
        }
    }

    private void broadcastMessage(P2PMessage message) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                "type", "MESSAGE",
                "message", message
            ));
        } catch (IOException e) {
            return;
        }
        
        TextMessage textMessage = new TextMessage(json);
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            } catch (IOException e) {
                log.warn("Failed to send message to session: {}", session.getId());
            }
        });
    }

    private void broadcastDelivery(String messageId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                "type", "DELIVERED",
                "messageId", messageId
            ));
        } catch (IOException e) {
            return;
        }
        
        TextMessage textMessage = new TextMessage(json);
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            } catch (IOException e) {
                log.warn("Failed to send delivery to session: {}", session.getId());
            }
        });
    }

    private void broadcastTyping(String peerId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                "type", "TYPING",
                "peerId", peerId
            ));
        } catch (IOException e) {
            return;
        }
        
        TextMessage textMessage = new TextMessage(json);
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            } catch (IOException e) {
                log.warn("Failed to send typing to session: {}", session.getId());
            }
        });
    }

    private void broadcastTypingStopped(String peerId) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                "type", "TYPING_STOPPED",
                "peerId", peerId
            ));
        } catch (IOException e) {
            return;
        }
        
        TextMessage textMessage = new TextMessage(json);
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            } catch (IOException e) {
                log.warn("Failed to send typing stopped to session: {}", session.getId());
            }
        });
    }

    private void broadcastAuthRequest(String peerId, String token) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                "type", "AUTH_REQUEST",
                "peerId", peerId,
                "token", token
            ));
        } catch (IOException e) {
            return;
        }
        
        TextMessage textMessage = new TextMessage(json);
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            } catch (IOException e) {
                log.warn("Failed to send auth request to session: {}", session.getId());
            }
        });
    }

    private void broadcastAuthResponse(String peerId, String token, boolean approved) {
        String json;
        try {
            json = objectMapper.writeValueAsString(Map.of(
                "type", "AUTH_RESPONSE",
                "peerId", peerId,
                "token", token,
                "approved", approved
            ));
        } catch (IOException e) {
            return;
        }
        
        TextMessage textMessage = new TextMessage(json);
        sessions.values().forEach(session -> {
            try {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            } catch (IOException e) {
                log.warn("Failed to send auth response to session: {}", session.getId());
            }
        });
    }

    // === Новые события: статусы, реакции, редактирование ===

    private void broadcastStatus(String peerId, String status, String statusMessage) {
        try {
            var payload = Map.of(
                "type", "STATUS_CHANGE",
                "peerId", peerId,
                "status", status,
                "statusMessage", statusMessage != null ? statusMessage : ""
            );
            TextMessage msg = new TextMessage(objectMapper.writeValueAsString(payload));
            sessions.values().forEach(s -> safeSend(s, msg));
        } catch (IOException ignored) {}
    }

    private void broadcastMessageRead(String messageId, String readerId) {
        try {
            var payload = Map.of(
                "type", "MESSAGE_READ",
                "messageId", messageId,
                "readerId", readerId
            );
            TextMessage msg = new TextMessage(objectMapper.writeValueAsString(payload));
            sessions.values().forEach(s -> safeSend(s, msg));
        } catch (IOException ignored) {}
    }

    private void broadcastMessageEdited(String messageId) {
        try {
            var payload = Map.of(
                "type", "MESSAGE_EDITED",
                "messageId", messageId
            );
            TextMessage msg = new TextMessage(objectMapper.writeValueAsString(payload));
            sessions.values().forEach(s -> safeSend(s, msg));
        } catch (IOException ignored) {}
    }

    private void broadcastMessageDeleted(String messageId) {
        try {
            var payload = Map.of(
                "type", "MESSAGE_DELETED",
                "messageId", messageId
            );
            TextMessage msg = new TextMessage(objectMapper.writeValueAsString(payload));
            sessions.values().forEach(s -> safeSend(s, msg));
        } catch (IOException ignored) {}
    }

    private void broadcastReaction(String messageId, String peerId, String emoji) {
        try {
            var payload = Map.of(
                "type", "REACTION_ADDED",
                "messageId", messageId,
                "peerId", peerId,
                "emoji", emoji
            );
            TextMessage msg = new TextMessage(objectMapper.writeValueAsString(payload));
            sessions.values().forEach(s -> safeSend(s, msg));
        } catch (IOException ignored) {}
    }

    /** Безопасная отправка сообщения в сессию */
    private void safeSend(WebSocketSession session, TextMessage message) {
        try {
            if (session.isOpen()) {
                session.sendMessage(message);
            }
        } catch (IOException e) {
            log.warn("Failed to send to session {}: {}", session.getId(), e.getMessage());
        }
    }
}
