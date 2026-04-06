package com.chat.p2p.repository;

import com.chat.p2p.entity.MessageEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, String> {
    List<MessageEntity> findByPeerIdOrderByTimestampAsc(String peerId);
    List<MessageEntity> findByPeerIdAndTimestampGreaterThanOrderByTimestampAsc(String peerId, java.time.LocalDateTime timestamp);
    long countByPeerId(String peerId);
    long countByPeerIdAndSynced(String peerId, boolean synced);
    List<MessageEntity> findByPeerIdAndDeliveredFalse(String peerId);
    long countByPeerIdAndDeliveredFalse(String peerId);
}
