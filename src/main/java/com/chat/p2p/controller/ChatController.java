package com.chat.p2p.controller;

import com.chat.p2p.model.Peer;
import com.chat.p2p.model.P2PMessage;
import com.chat.p2p.repository.FileRepository;
import com.chat.p2p.service.DatabaseService;
import com.chat.p2p.service.P2PNetworkService;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.*;

@RestController
public class ChatController {
    
    @Autowired
    private P2PNetworkService networkService;

    @Autowired
    private DatabaseService databaseService;

    @Autowired
    private FileRepository fileRepository;

    private final List<P2PMessage> pendingMessages = Collections.synchronizedList(new ArrayList<>());
    private final List<FileTransferProgress> transferProgress = Collections.synchronizedList(new ArrayList<>());
    private final Set<String> processedFiles = Collections.synchronizedSet(new HashSet<>());
    private volatile boolean registered = false;

    @PostConstruct
    public void init() {
        networkService.addListener(message -> {
            if ("MESSAGE".equals(message.getType())) {
                System.out.println("Listener received message: " + message.getContent());
                pendingMessages.add(message);
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
                    e.printStackTrace();
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
    public void setName(@RequestParam String name) {
        networkService.setPeerName(name);
        System.out.println("Name set to: " + name);
    }

    @PostMapping("/api/send")
    public void sendMessage(@RequestBody P2PMessage message) {
        String senderId = networkService.getPeerId();
        String targetId = message.getTargetId();
        
        try {
            databaseService.saveMessage(targetId, senderId, message.getSenderName(), 
                message.getContent(), "MESSAGE", message.getFileId());
        } catch (Exception e) {
            System.err.println("DB error: " + e.getMessage());
        }

        message.setSenderId(senderId);
        networkService.sendMessage(targetId, message);
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
                System.out.println("Returning " + messages.size() + " pending messages");
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
    public void sendFile(@RequestBody SendFileRequest request) {
        var entity = fileRepository.findById(request.fileId).orElse(null);
        if (entity != null) {
            String filePath = entity.getFilePath();
            System.out.println("Sending file: " + filePath + " to " + request.targetId);
            networkService.sendFile(request.targetId, request.fileId, request.fileName, request.fileSize, filePath);
        } else {
            System.err.println("File not found in DB: " + request.fileId);
        }
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
