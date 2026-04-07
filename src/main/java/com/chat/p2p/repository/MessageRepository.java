package com.chat.p2p.repository;

import com.chat.p2p.entity.MessageEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<MessageEntity, String> {
    List<MessageEntity> findByPeerIdOrderByTimestampAsc(String peerId);
    List<MessageEntity> findByPeerIdAndTimestampGreaterThanOrderByTimestampAsc(String peerId, LocalDateTime timestamp);
    long countByPeerId(String peerId);
    long countByPeerIdAndSynced(String peerId, boolean synced);
    List<MessageEntity> findByPeerIdAndDeliveredFalse(String peerId);
    long countByPeerIdAndDeliveredFalse(String peerId);

    /** SQL LIKE поиск с пагинацией */
    @Query("SELECT m FROM MessageEntity m WHERE m.content LIKE %:query% ORDER BY m.timestamp DESC")
    Page<MessageEntity> searchByContent(@Param("query") String query, Pageable pageable);

    /** SQL LIKE поиск для конкретного пира */
    @Query("SELECT m FROM MessageEntity m WHERE m.peerId = :peerId AND m.content LIKE %:query% ORDER BY m.timestamp DESC")
    Page<MessageEntity> searchByContentAndPeer(@Param("peerId") String peerId, @Param("query") String query, Pageable pageable);

    /** Удалить сообщения старше указанной даты (для TTL очистки) */
    @Query("DELETE FROM MessageEntity m WHERE m.timestamp < :before")
    void deleteOlderThan(@Param("before") LocalDateTime before);

    /** Количество сообщений для очистки */
    long countByTimestampBefore(LocalDateTime before);

    /** Закреплённые сообщения для пира */
    List<MessageEntity> findByPeerIdAndPinnedTrue(String peerId);

    /** Сообщения без удалённых */
    List<MessageEntity> findByPeerIdAndDeletedFalseOrderByTimestampAsc(String peerId);

    /** Сообщения от конкретного отправителя */
    List<MessageEntity> findBySenderIdAndId(String senderId, String messageId);
}
