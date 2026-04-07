package com.chat.p2p.controller;

import com.chat.p2p.entity.MessageEntity;
import com.chat.p2p.repository.MessageRepository;
import com.chat.p2p.service.P2PNetworkService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Взаимодействие с сообщениями: реакции, редактирование, удаление, закрепление.
 */
@RestController
@RequestMapping("/api/messages")
public class MessageInteractionController {
    private static final Logger log = LoggerFactory.getLogger(MessageInteractionController.class);
    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private P2PNetworkService networkService;

    /**
     * Добавить реакцию к сообщению.
     * Поддерживаемые эмодзи: 👍 ❤️ 😂 😮 😢 🔥
     */
    @PostMapping("/{messageId}/react")
    public Map<String, Object> addReaction(
            @PathVariable String messageId,
            @RequestBody Map<String, String> request) {

        String emoji = request.get("emoji");
        if (emoji == null || emoji.isBlank()) {
            return Map.of("error", "Emoji is required");
        }

        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return Map.of("error", "Message not found");
        }

        // Парсим существующие реакции
        Map<String, String> reactions;
        try {
            reactions = msg.getReactions() != null
                    ? objectMapper.readValue(msg.getReactions(), new TypeReference<>() {})
                    : new java.util.HashMap<>();
        } catch (Exception e) {
            reactions = new java.util.HashMap<>();
        }

        // Добавляем/обновляем реакцию (peerId берём из контекста)
        String peerId = networkService.getPeerId();
        reactions.put(peerId, emoji);

        try {
            msg.setReactions(objectMapper.writeValueAsString(reactions));
            messageRepository.save(msg);

            // Уведомляем через WebSocket
            for (var listener : networkService.getListeners()) {
                listener.onReactionAdded(messageId, peerId, emoji);
            }

            log.info("Reaction {} added to message {}", emoji, messageId);
            return Map.of("status", "ok", "reactions", reactions);
        } catch (Exception e) {
            log.error("Failed to save reaction: {}", e.getMessage());
            return Map.of("error", "Failed to save reaction");
        }
    }

    /**
     * Удалить свою реакцию.
     */
    @DeleteMapping("/{messageId}/react")
    public Map<String, Object> removeReaction(@PathVariable String messageId) {
        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return Map.of("error", "Message not found");
        }

        String peerId = networkService.getPeerId();
        Map<String, String> reactions;
        try {
            reactions = msg.getReactions() != null
                    ? objectMapper.readValue(msg.getReactions(), new TypeReference<>() {})
                    : new java.util.HashMap<>();
        } catch (Exception e) {
            return Map.of("error", "Failed to parse reactions");
        }

        reactions.remove(peerId);

        try {
            msg.setReactions(objectMapper.writeValueAsString(reactions));
            messageRepository.save(msg);
            return Map.of("status", "ok", "reactions", reactions);
        } catch (Exception e) {
            return Map.of("error", "Failed to update reactions");
        }
    }

    /**
     * Редактировать своё сообщение.
     */
    @PutMapping("/{messageId}")
    public Map<String, Object> editMessage(
            @PathVariable String messageId,
            @RequestBody Map<String, String> request) {

        String content = request.get("content");
        if (content == null || content.isBlank()) {
            return Map.of("error", "Content is required");
        }
        if (content.length() > 10000) {
            return Map.of("error", "Message too long (max 10000 chars)");
        }

        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return Map.of("error", "Message not found");
        }

        // Проверка: только автор может редактировать
        if (!msg.getSenderId().equals(networkService.getPeerId())) {
            return Map.of("error", "Only the author can edit messages");
        }

        msg.setContent(content);
        msg.setEdited(true);
        msg.setEditedAt(LocalDateTime.now());
        messageRepository.save(msg);

        // Уведомляем через WebSocket
        for (var listener : networkService.getListeners()) {
            listener.onMessageEdited(messageId);
        }

        log.info("Message {} edited by {}", messageId, msg.getSenderId());
        return Map.of("status", "ok", "editedAt", msg.getEditedAt().toString());
    }

    /**
     * Удалить своё сообщение (soft delete).
     */
    @DeleteMapping("/{messageId}")
    public Map<String, Object> deleteMessage(@PathVariable String messageId) {
        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return Map.of("error", "Message not found");
        }

        if (!msg.getSenderId().equals(networkService.getPeerId())) {
            return Map.of("error", "Only the author can delete messages");
        }

        msg.setDeleted(true);
        msg.setContent("[deleted]");
        messageRepository.save(msg);

        for (var listener : networkService.getListeners()) {
            listener.onMessageDeleted(messageId);
        }

        log.info("Message {} deleted by {}", messageId, msg.getSenderId());
        return Map.of("status", "ok");
    }

    /**
     * Закрепить/открепить сообщение.
     */
    @PostMapping("/{messageId}/pin")
    public Map<String, Object> pinMessage(
            @PathVariable String messageId,
            @RequestBody(required = false) Map<String, Boolean> request) {

        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return Map.of("error", "Message not found");
        }

        boolean pin = request != null && request.containsKey("pin")
                ? request.get("pin")
                : !msg.isPinned();

        msg.setPinned(pin);
        messageRepository.save(msg);

        log.info("Message {} {} by {}", messageId, pin ? "pinned" : "unpinned", msg.getSenderId());
        return Map.of("status", "ok", "pinned", pin);
    }

    /**
     * Отметить сообщение как прочитанное.
     */
    @PostMapping("/{messageId}/read")
    public Map<String, Object> markAsRead(@PathVariable String messageId) {
        MessageEntity msg = messageRepository.findById(messageId).orElse(null);
        if (msg == null) {
            return Map.of("error", "Message not found");
        }

        if (!msg.isDelivered()) {
            msg.setDelivered(true);
            msg.setDeliveredAt(LocalDateTime.now());
        }

        msg.setReadStatus(true);
        msg.setReadAt(LocalDateTime.now());
        messageRepository.save(msg);

        for (var listener : networkService.getListeners()) {
            listener.onMessageRead(messageId, networkService.getPeerId());
        }

        return Map.of("status", "ok");
    }

    /**
     * Получить закреплённые сообщения для пира.
     */
    @GetMapping("/pinned")
    public Map<String, Object> getPinnedMessages(@RequestParam String peerId) {
        var pinned = messageRepository.findByPeerIdAndPinnedTrue(peerId);
        return Map.of("pinned", pinned);
    }
}
