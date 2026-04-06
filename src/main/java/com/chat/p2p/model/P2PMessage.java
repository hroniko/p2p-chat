package com.chat.p2p.model;

import java.util.UUID;

public class P2PMessage {
    private String id = UUID.randomUUID().toString();
    private String type;
    private String senderId;
    private String senderName;
    private String targetId;
    private String content;
    private String encryptedContent; // E2E зашифрованный контент
    private String fileId;
    private String fileName;
    private String fileType;
    private long fileSize;
    private String time;
    private String authToken;
    private long timestamp = System.currentTimeMillis();

    public P2PMessage() {}

    public P2PMessage(String type, String senderId, String senderName, String content) {
        this.type = type;
        this.senderId = senderId;
        this.senderName = senderName;
        this.content = content;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getEncryptedContent() { return encryptedContent; }
    public void setEncryptedContent(String encryptedContent) { this.encryptedContent = encryptedContent; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFileType() { return fileType; }
    public void setFileType(String fileType) { this.fileType = fileType; }

    public long getFileSize() { return fileSize; }
    public void setFileSize(long fileSize) { this.fileSize = fileSize; }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getAuthToken() { return authToken; }
    public void setAuthToken(String authToken) { this.authToken = authToken; }

    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
