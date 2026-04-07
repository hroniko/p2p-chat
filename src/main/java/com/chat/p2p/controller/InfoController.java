package com.chat.p2p.controller;

import com.chat.p2p.entity.MessageEntity;
import com.chat.p2p.repository.MessageRepository;
import com.chat.p2p.service.AvatarService;
import com.chat.p2p.service.P2PNetworkService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Информация о текущем пире.
 */
@RestController
public class InfoController {

    @Autowired
    private P2PNetworkService networkService;

    @Autowired(required = false)
    private AvatarService avatarService;

    @Autowired(required = false)
    private MessageRepository messageRepository;

    @GetMapping("/api/info")
    public Map<String, Object> getInfo() {
        String peerId = networkService.getPeerId();
        var avatar = avatarService != null ? avatarService.getAvatar(peerId) : null;
        long msgCount = messageRepository != null ? messageRepository.countByPeerId(peerId) : 0;

        return Map.of(
            "peerId", peerId,
            "p2pPort", networkService.getP2pPort(),
            "serverPort", networkService.getServerPort(),
            "publicAddress", networkService.getPublicAddress(),
            "name", networkService.getPeerName(),
            "bio", networkService.getBio() != null ? networkService.getBio() : "",
            "avatarUrl", avatar != null ? "/api/avatar/" + peerId : null,
            "thumbUrl", avatar != null ? "/api/avatar/" + peerId + "/thumb" : null,
            "messageCount", msgCount
        );
    }

    /**
     * Получить статистику активности.
     */
    @GetMapping("/api/stats")
    public Map<String, Object> getStats() {
        String peerId = networkService.getPeerId();
        if (messageRepository == null) {
            return Map.of("messageCount", 0, "filesCount", 0);
        }

        List<MessageEntity> allMessages = messageRepository.findByPeerIdAndDeletedFalseOrderByTimestampAsc(peerId);
        long sentCount = allMessages.stream().filter(m -> m.getSenderId().equals(peerId)).count();
        long receivedCount = allMessages.stream().filter(m -> !m.getSenderId().equals(peerId)).count();
        long filesCount = allMessages.stream().filter(m -> m.getFileId() != null).count();

        // Топ собеседников
        Map<String, Long> topPeers = allMessages.stream()
                .filter(m -> !m.getSenderId().equals(peerId))
                .collect(java.util.stream.Collectors.groupingBy(
                        MessageEntity::getSenderId,
                        java.util.stream.Collectors.counting()
                ));

        return Map.of(
            "totalMessages", allMessages.size(),
            "sentMessages", sentCount,
            "receivedMessages", receivedCount,
            "filesCount", filesCount,
            "uniquePeers", allMessages.stream().map(MessageEntity::getSenderId).filter(id -> !id.equals(peerId)).distinct().count(),
            "topPeers", topPeers
        );
    }
}
