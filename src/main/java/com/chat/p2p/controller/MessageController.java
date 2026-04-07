package com.chat.p2p.controller;

import com.chat.p2p.model.P2PMessage;
import com.chat.p2p.service.DatabaseService;
import com.chat.p2p.service.P2PNetworkService;
import com.chat.p2p.service.SharedState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Управление сообщениями: отправка, история, поиск, входящие.
 */
@RestController
@RequestMapping("/api")
public class MessageController {
    private static final Logger log = LoggerFactory.getLogger(MessageController.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 60;
    private final java.util.Map<String, RateLimitEntry> rateLimitMap = new java.util.concurrent.ConcurrentHashMap<>();

    @Autowired
    private P2PNetworkService networkService;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private SharedState sharedState;

    /**
     * Отправить сообщение пиру.
     */
    @PostMapping("/send")
    public Map<String, Object> sendMessage(@RequestBody P2PMessage message,
                                           @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId != null && !checkRateLimit(clientId)) {
            log.warn("Rate limit exceeded for client: {}", clientId);
            return Map.of("error", "Rate limit exceeded");
        }

        // Валидация
        if (message.getTargetId() == null || message.getTargetId().isBlank()) {
            return Map.of("error", "Target peer ID is required");
        }
        if (message.getContent() == null || message.getContent().isBlank()) {
            return Map.of("error", "Message content is required");
        }
        if (message.getContent().length() > 10000) {
            return Map.of("error", "Message too long (max 10000 chars)");
        }
        if (message.getSenderName() != null && message.getSenderName().length() > 50) {
            return Map.of("error", "Sender name too long (max 50 chars)");
        }

        String senderId = networkService.getPeerId();
        String targetId = message.getTargetId();

        try {
            databaseService.saveMessage(targetId, senderId, message.getSenderName(),
                message.getContent(), "MESSAGE", message.getFileId());
        } catch (Exception e) {
            log.error("DB error while saving message: {}", e.getMessage());
        }

        message.setSenderId(senderId);
        networkService.sendMessage(targetId, message);

        return Map.of("id", message.getId() != null ? message.getId() : "");
    }

    /**
     * История сообщений с указанным пиром.
     */
    @GetMapping("/messages/{peerId}")
    public List<DatabaseService.MessageDto> getMessages(@PathVariable String peerId) {
        return databaseService.getMessagesDto(peerId);
    }

    /**
     * Получить входящие сообщения (буфер очищается при чтении).
     */
    @GetMapping("/pending-messages")
    public List<P2PMessage> getPendingMessages() {
        return sharedState.getPendingMessages();
    }

    /**
     * Поиск сообщений с пагинацией через SQL LIKE.
     *
     * @param q      поисковый запрос
     * @param page   номер страницы (0-based)
     * @param size   размер страницы (1-200)
     * @param peerId фильтр по пиру (опционально)
     */
    @GetMapping("/search")
    public Map<String, Object> searchMessages(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String peerId) {

        if (q == null || q.isBlank()) {
            return Map.of("error", "Search query is required", "results", List.of());
        }
        if (q.length() > 200) {
            return Map.of("error", "Search query too long (max 200 chars)");
        }
        if (page < 0) page = 0;
        if (size < 1 || size > 200) size = 50;

        Page<DatabaseService.MessageDto> result;
        if (peerId != null && !peerId.isBlank()) {
            result = databaseService.searchMessages(peerId, q, page, size);
        } else {
            result = databaseService.searchMessages(q, page, size);
        }

        return Map.of(
            "results", result.getContent(),
            "totalPages", result.getTotalPages(),
            "totalElements", result.getTotalElements(),
            "currentPage", page,
            "size", size
        );
    }

    /**
     * Глобальный обработчик ошибок валидации.
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public Map<String, String> handleValidationError(IllegalArgumentException e) {
        return Map.of("error", e.getMessage());
    }

    private boolean checkRateLimit(String clientId) {
        long now = System.currentTimeMillis();
        RateLimitEntry entry = rateLimitMap.compute(clientId, (k, v) -> {
            if (v == null || now - v.windowStart > 60000) {
                return new RateLimitEntry(now);
            }
            return v;
        });

        synchronized (entry) {
            if (now - entry.windowStart > 60000) {
                entry.windowStart = now;
                entry.count = 0;
            }
            entry.count++;
            return entry.count <= MAX_REQUESTS_PER_MINUTE;
        }
    }

    private static class RateLimitEntry {
        int count;
        long windowStart;

        RateLimitEntry(long windowStart) {
            this.count = 0;
            this.windowStart = windowStart;
        }
    }
}
