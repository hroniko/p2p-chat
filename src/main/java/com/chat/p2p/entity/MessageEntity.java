package com.chat.p2p.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "messages")
public class MessageEntity {
    @Id
    private String id;
    
    @Column(name = "peer_id")
    private String peerId;
    
    @Column(name = "sender_id")
    private String senderId;
    
    @Column(name = "sender_name")
    private String senderName;
    
    @Column(name = "content")
    private String content;
    
    @Column(name = "type")
    private String type;
    
    @Column(name = "file_id")
    private String fileId;
    
    @Column(name = "timestamp")
    private LocalDateTime timestamp;
    
    @Column(name = "synced")
    private boolean synced;
    
    @Column(name = "delivered")
    private boolean delivered;

    public MessageEntity() {
        this.id = UUID.randomUUID().toString();
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getFileId() { return fileId; }
    public void setFileId(String fileId) { this.fileId = fileId; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public boolean isSynced() { return synced; }
    public void setSynced(boolean synced) { this.synced = synced; }

    public boolean isDelivered() { return delivered; }
    public void setDelivered(boolean delivered) { this.delivered = delivered; }
}
