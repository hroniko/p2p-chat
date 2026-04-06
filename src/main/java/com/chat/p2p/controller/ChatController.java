package com.chat.p2p.controller;

import com.chat.p2p.model.Group;
import com.chat.p2p.model.Peer;
import com.chat.p2p.model.P2PMessage;
import com.chat.p2p.repository.FileRepository;
import com.chat.p2p.service.DatabaseService;
import com.chat.p2p.service.GroupService;
import com.chat.p2p.service.P2PNetworkService;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * REST контроллер. Обрабатывает HTTP запросы от клиента.
 * 
 * Это прослойка между фронтендом и P2P сетью.
 * Фронтенд стучится сюда, мы перенаправляем в P2P сеть.
 * 
 * @RestController = @Controller + @ResponseBody
 * Всё возвращается как JSON - современно, модно, молодежно.
 */
@RestController
public class ChatController {
    private static final Logger log = LoggerFactory.getLogger(ChatController.class);
    private static final int MAX_REQUESTS_PER_MINUTE = 60; // 60 запросов в минуту - достаточно для чата
    private static final Map<String, RateLimitEntry> rateLimitMap = new ConcurrentHashMap<>(); // Карта ограничений
    
    @Autowired
    private P2PNetworkService networkService; // Сердце P2P - внедряем через конструктор/поле

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private FileRepository fileRepository;
    
    @Autowired
    private GroupService groupService;

    private final List<P2PMessage> pendingMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<FileTransferProgress> transferProgress = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> processedFiles = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean registered = false;

    private static class RateLimitEntry {
        int count;
        long windowStart;

        RateLimitEntry(long windowStart) {
            this.count = 0;
            this.windowStart = windowStart;
        }
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

    @PostConstruct
    public void init() {
        networkService.addListener(new P2PNetworkService.NetworkListener() {
            @Override
            public void onMessageReceived(P2PMessage message) {
                if ("MESSAGE".equals(message.getType())) {
                    log.debug("Listener received message: {}", message.getContent());
                    pendingMessages.add(message);
                    
                    if (message.getId() != null) {
                        networkService.sendDeliveryConfirmation(message.getSenderId(), message.getId());
                    }
                }
            }

            @Override
            public void onAuthRequest(String peerId, String token, String secret) {
                log.info("Auth request from {} with token {}", peerId, token);
            }

            @Override
            public void onAuthResponse(String peerId, String token, boolean approved) {
                log.info("Auth response from {}: {}", peerId, approved ? "approved" : "rejected");
            }
        });
        
        networkService.addFileListener(new P2PNetworkService.FileTransferListener() {
                @Override
                public void onProgress(String transferId, long received, long total) {
                    synchronized (transferProgress) {
                        transferProgress.removeIf(p -> p.transferId.equals(transferId));
                        transferProgress.add(new FileTransferProgress(transferId, received, total, false));
                    }
                }

                @Override
                public void onComplete(String transferId, String fileName, long fileSize) {
                }

                @Override
                public void onFileComplete(String transferId, String fileId, String fileName, long fileSize, String senderId) {
                try {
                    Path src = Paths.get("files", fileName);
                    if (Files.exists(src)) {
                        String ext = fileName.contains(".") ? fileName.substring(fileName.lastIndexOf(".")) : "";
                        Path dest = Paths.get("files", fileId + ext);
                        Files.move(src, dest);
                        
                        var entity = new com.chat.p2p.entity.FileEntity();
                        entity.setId(fileId);
                        entity.setPeerId(senderId);
                        entity.setSenderId(senderId);
                        entity.setFileName(fileName);
                        entity.setFilePath(dest.getFileName().toString());
                        entity.setFileSize(fileSize);
                        entity.setTimestamp(LocalDateTime.now());
                        fileRepository.save(entity);
                    }
                } catch (IOException e) {
                    log.error("Error saving file: {}", e.getMessage());
                }
                
                synchronized (transferProgress) {
                    transferProgress.removeIf(p -> p.transferId.equals(transferId));
                    transferProgress.add(new FileTransferProgress(transferId, fileSize, fileSize, true));
                }
                
                if (!processedFiles.contains(transferId)) {
                    processedFiles.add(transferId);
                    P2PMessage msg = new P2PMessage("MESSAGE", senderId, "", "[File] " + fileName);
                    msg.setFileId(fileId);
                    msg.setFileName(fileName);
                    msg.setFileSize(fileSize);
                    pendingMessages.add(msg);
                }
            }
        });
    }

    @GetMapping("/api/info")
    public Map<String, Object> getInfo() {
        return Map.of(
            "peerId", networkService.getPeerId(),
            "p2pPort", networkService.getP2pPort()
        );
    }

    @GetMapping("/api/peers")
    public Map<String, Peer> getPeers() {
        return networkService.getPeers();
    }

    @PostMapping("/api/set-name")
    public void setName(@RequestParam String name, @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId != null && !checkRateLimit(clientId)) {
            log.warn("Rate limit exceeded for client: {}", clientId);
            return;
        }
        networkService.setPeerName(name);
        log.info("Name set to: {}", name);
    }

    @PostMapping("/api/send")
    public Map<String, Object> sendMessage(@RequestBody P2PMessage message, @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId != null && !checkRateLimit(clientId)) {
            log.warn("Rate limit exceeded for client: {}", clientId);
            return Map.of("error", "Rate limit exceeded");
        }
        
        String senderId = networkService.getPeerId();
        String targetId = message.getTargetId();
        
        try {
            databaseService.saveMessage(targetId, senderId, message.getSenderName(), 
                message.getContent(), "MESSAGE", message.getFileId());
        } catch (Exception e) {
            log.error("DB error: {}", e.getMessage());
        }

        message.setSenderId(senderId);
        networkService.sendMessage(targetId, message);
        
        return Map.of("id", message.getId() != null ? message.getId() : "");
    }

    @GetMapping("/api/messages/{peerId}")
    public List<DatabaseService.MessageDto> getMessages(@PathVariable String peerId) {
        return databaseService.getMessagesDto(peerId);
    }

    @GetMapping("/api/pending-messages")
    public List<P2PMessage> getPendingMessages() {
        synchronized (pendingMessages) {
            List<P2PMessage> messages = new ArrayList<>(pendingMessages);
            if (!messages.isEmpty()) {
                log.debug("Returning {} pending messages", messages.size());
            }
            pendingMessages.clear();
            return messages;
        }
    }

    @GetMapping("/api/transfer-progress")
    public List<FileTransferProgress> getTransferProgress() {
        synchronized (transferProgress) {
            return new ArrayList<>(transferProgress);
        }
    }

    @PostMapping("/api/send-file")
    public void sendFile(@RequestBody SendFileRequest request, @RequestHeader(value = "X-Client-Id", required = false) String clientId) {
        if (clientId != null && !checkRateLimit(clientId)) {
            log.warn("Rate limit exceeded for client: {}", clientId);
            return;
        }
        
        var entity = fileRepository.findById(request.fileId).orElse(null);
        if (entity != null) {
            String filePath = entity.getFilePath();
            log.info("Sending file: {} to {}", filePath, request.targetId);
            networkService.sendFile(request.targetId, request.fileId, request.fileName, request.fileSize, filePath);
        } else {
            log.warn("File not found in DB: {}", request.fileId);
        }
    }

    @GetMapping("/api/search")
    public List<DatabaseService.MessageDto> searchMessages(@RequestParam String q) {
        return databaseService.searchMessages(q);
    }

    @PostMapping("/api/auth/request")
    public Map<String, Object> requestAuth(@RequestBody Map<String, String> request) {
        String targetPeerId = request.get("peerId");
        String secret = request.get("secret");
        
        if (secret == null || secret.length() < 4) {
            return Map.of("error", "Secret must be at least 4 characters");
        }
        
        String token = UUID.randomUUID().toString();
        networkService.requestAuth(targetPeerId, token, secret);
        
        return Map.of("status", "request_sent", "token", token);
    }

    @PostMapping("/api/auth/respond")
    public Map<String, Object> respondAuth(@RequestBody Map<String, String> request) {
        String peerId = request.get("peerId");
        String token = request.get("token");
        boolean approved = Boolean.parseBoolean(request.get("approved"));
        
        networkService.respondAuth(peerId, token, approved);
        
        return Map.of("status", approved ? "trusted" : "rejected");
    }

    @GetMapping("/api/peers/trusted")
    public Map<String, Peer> getTrustedPeers() {
        return networkService.getTrustedPeers();
    }

    // === Групповые чаты ===

    @PostMapping("/api/groups/create")
    public Map<String, Object> createGroup(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String creatorId = networkService.getPeerId();
        
        Group group = groupService.createGroup(name, creatorId);
        
        return Map.of(
            "groupId", group.getId(),
            "name", group.getName(),
            "members", group.getMembers()
        );
    }

    @GetMapping("/api/groups")
    public List<Group> getGroups() {
        return groupService.getLocalGroups();
    }

    @GetMapping("/api/groups/{groupId}")
    public Group getGroup(@PathVariable String groupId) {
        return groupService.getGroup(groupId);
    }

    @PostMapping("/api/groups/{groupId}/join")
    public Map<String, Object> joinGroup(@PathVariable String groupId, @RequestBody Group group) {
        groupService.joinGroup(group);
        return Map.of("status", "joined");
    }

    @PostMapping("/api/groups/{groupId}/members")
    public void addMember(@PathVariable String groupId, @RequestBody Map<String, String> request) {
        String peerId = request.get("peerId");
        groupService.addMember(groupId, peerId);
    }

    @DeleteMapping("/api/groups/{groupId}/members/{peerId}")
    public void removeMember(@PathVariable String groupId, @PathVariable String peerId) {
        groupService.removeMember(groupId, peerId);
    }

    public static class FileTransferProgress {
        public String transferId;
        public long received;
        public long total;
        public boolean complete;

        public FileTransferProgress(String transferId, long received, long total, boolean complete) {
            this.transferId = transferId;
            this.received = received;
            this.total = total;
            this.complete = complete;
        }
    }

    public static class SendFileRequest {
        public String targetId;
        public String fileId;
        public String fileName;
        public long fileSize;
        public String filePath;
    }
}
