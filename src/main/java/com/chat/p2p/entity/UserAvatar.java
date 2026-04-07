package com.chat.p2p.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Аватар пользователя.
 * Хранит путь к файлу аватара и его thumbnail.
 */
@Entity
@Table(name = "user_avatars")
public class UserAvatar {

    @Id
    private String peerId;

    @Column(nullable = false)
    private String filePath;

    private String thumbnailPath;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    public UserAvatar() {
        this.uploadedAt = LocalDateTime.now();
    }

    public UserAvatar(String peerId, String filePath, String thumbnailPath) {
        this.peerId = peerId;
        this.filePath = filePath;
        this.thumbnailPath = thumbnailPath;
        this.uploadedAt = LocalDateTime.now();
    }

    public String getPeerId() { return peerId; }
    public void setPeerId(String peerId) { this.peerId = peerId; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getThumbnailPath() { return thumbnailPath; }
    public void setThumbnailPath(String thumbnailPath) { this.thumbnailPath = thumbnailPath; }

    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
