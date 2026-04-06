package com.chat.p2p.service;

import com.chat.p2p.entity.MessageEntity;
import com.chat.p2p.repository.MessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Сервис истории сообщений для оффлайн пиров.
 * 
 * Когда пир был оффлайн, его сообщения сохраняются в БД.
 * При возвращении онлайн - отправляем накопившиеся сообщения.
 */
@Service
public class OfflineMessageService {
    private static final Logger log = LoggerFactory.getLogger(OfflineMessageService.class);

    @Autowired
    private MessageRepository messageRepository;

    @Autowired
    private P2PNetworkService networkService;

    /** Пиры, которым нужно отправить оффлайн сообщения */
    private final ConcurrentHashMap<String, Long> pendingPeers = new ConcurrentHashMap<>();

    /**
     * Сохраняем сообщение для оффлайн пира.
     * Вызывается когда пир оффлайн.
     */
    public void storeOfflineMessage(String peerId, String senderId, String senderName, 
                                    String content, String type, String fileId) {
        try {
            MessageEntity entity = new MessageEntity();
            entity.setPeerId(peerId);
            entity.setSenderId(senderId);
            entity.setSenderName(senderName);
            entity.setContent(content);
            entity.setType(type);
            entity.setFileId(fileId);
            entity.setTimestamp(LocalDateTime.now());
            entity.setSynced(false); // Не отправлено
            entity.setDelivered(false); // Не доставлено
            
            messageRepository.save(entity);
            log.info("Stored offline message for peer: {}", peerId);
        } catch (Exception e) {
            log.error("Failed to store offline message: {}", e.getMessage());
        }
    }

    /**
     * Регистрируем пира для отправки оффлайн сообщений.
     * Вызывается когда пир подключается.
     */
    public void onPeerOnline(String peerId) {
        pendingPeers.put(peerId, System.currentTimeMillis());
        log.info("Peer {} is now online, will send offline messages", peerId);
    }

    /**
     * Отправляем накопившиеся сообщения пиру.
     */
    public void sendOfflineMessages(String peerId) {
        try {
            List<MessageEntity> pending = messageRepository
                .findByPeerIdAndDeliveredFalse(peerId);
            
            if (pending.isEmpty()) {
                log.debug("No pending messages for peer: {}", peerId);
                return;
            }

            log.info("Sending {} offline messages to peer: {}", pending.size(), peerId);
            
            for (MessageEntity entity : pending) {
                var message = new com.chat.p2p.model.P2PMessage(
                    entity.getType(),
                    entity.getSenderId(),
                    entity.getSenderName(),
                    entity.getContent()
                );
                message.setFileId(entity.getFileId());
                
                networkService.sendMessage(peerId, message);
                
                // Помечаем как отправленное
                entity.setDelivered(true);
                messageRepository.save(entity);
            }
            
            log.info("All offline messages sent to peer: {}", peerId);
        } catch (Exception e) {
            log.error("Failed to send offline messages to {}: {}", peerId, e.getMessage());
        }
    }

    /**
     * Периодически проверяем - есть ли пиры для отправки.
     */
    @Scheduled(fixedRate = 5000)
    public void checkPendingPeers() {
        long now = System.currentTimeMillis();
        
        pendingPeers.entrySet().removeIf(entry -> {
            String peerId = entry.getKey();
            
            // Проверяем что пир действительно онлайн
            if (networkService.getPeers().containsKey(peerId)) {
                sendOfflineMessages(peerId);
                return true; // Удаляем из очереди
            }
            
            // Если пир офлайн уже больше 30 сек - удаляем
            if (now - entry.getValue() > 30000) {
                return true;
            }
            
            return false;
        });
    }

    /**
     * Получаем количество неотправленных сообщений для пира.
     */
    public long getPendingCount(String peerId) {
        return messageRepository.countByPeerIdAndDeliveredFalse(peerId);
    }
}