package com.chat.p2p.service;

import com.chat.p2p.entity.MessageEntity;
import com.chat.p2p.repository.MessageRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class DatabaseService {
    
    @Autowired
    private MessageRepository messageRepository;

    public void saveMessage(String peerId, String senderId, String senderName, String content, String type, String fileId) {
        MessageEntity entity = new MessageEntity();
        entity.setPeerId(peerId);
        entity.setSenderId(senderId);
        entity.setSenderName(senderName);
        entity.setContent(content);
        entity.setType(type);
        entity.setFileId(fileId);
        entity.setTimestamp(LocalDateTime.now());
        entity.setSynced(false);
        messageRepository.save(entity);
    }

    public List<MessageEntity> getMessagesForPeer(String peerId) {
        return messageRepository.findByPeerIdOrderByTimestampAsc(peerId);
    }

    public List<MessageDto> getMessagesDto(String peerId) {
        return messageRepository.findByPeerIdOrderByTimestampAsc(peerId)
                .stream()
                .map(MessageDto::fromEntity)
                .collect(Collectors.toList());
    }

    public void markAsSynced(String messageId) {
        messageRepository.findById(messageId).ifPresent(m -> {
            m.setSynced(true);
            messageRepository.save(m);
        });
    }

    public void syncMessages(List<MessageDto> messages) {
        for (MessageDto dto : messages) {
            boolean exists = messageRepository.findByPeerIdOrderByTimestampAsc(dto.peerId)
                    .stream()
                    .anyMatch(m -> m.getTimestamp().equals(dto.timestamp) && m.getSenderId().equals(dto.senderId));
            
            if (!exists) {
                MessageEntity entity = dto.toEntity();
                entity.setSynced(true);
                messageRepository.save(entity);
            }
        }
    }

    public long getMessageCount(String peerId) {
        return messageRepository.countByPeerId(peerId);
    }

    public long getUnsyncedCount(String peerId) {
        return messageRepository.countByPeerIdAndSynced(peerId, false);
    }

    public LocalDateTime getLastMessageTime(String peerId) {
        List<MessageEntity> messages = messageRepository.findByPeerIdOrderByTimestampAsc(peerId);
        if (messages.isEmpty()) return null;
        return messages.get(messages.size() - 1).getTimestamp();
    }

    public static class MessageDto {
        public String id;
        public String peerId;
        public String senderId;
        public String senderName;
        public String content;
        public String type;
        public String fileId;
        public LocalDateTime timestamp;

        public static MessageDto fromEntity(MessageEntity entity) {
            MessageDto dto = new MessageDto();
            dto.id = entity.getId();
            dto.peerId = entity.getPeerId();
            dto.senderId = entity.getSenderId();
            dto.senderName = entity.getSenderName();
            dto.content = entity.getContent();
            dto.type = entity.getType();
            dto.fileId = entity.getFileId();
            dto.timestamp = entity.getTimestamp();
            return dto;
        }

        public MessageEntity toEntity() {
            MessageEntity entity = new MessageEntity();
            entity.setId(id);
            entity.setPeerId(peerId);
            entity.setSenderId(senderId);
            entity.setSenderName(senderName);
            entity.setContent(content);
            entity.setType(type);
            entity.setFileId(fileId);
            entity.setTimestamp(timestamp != null ? timestamp : LocalDateTime.now());
            return entity;
        }
    }
}
