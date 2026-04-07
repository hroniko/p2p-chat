package com.chat.p2p.controller;

import com.chat.p2p.entity.UserAvatar;
import com.chat.p2p.service.AvatarService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * Управление аватарами пользователей.
 */
@RestController
@RequestMapping("/api/avatar")
public class AvatarController {

    @Autowired
    private AvatarService avatarService;

    /**
     * Загрузить аватар.
     */
    @PostMapping("/upload")
    public Map<String, Object> uploadAvatar(@RequestParam("file") MultipartFile file) {
        // peerId берётся из заголовка (установленный peer)
        String peerId = "self"; // TODO: брать из контекста

        UserAvatar avatar = avatarService.uploadAvatar(peerId, file);
        return Map.of(
            "peerId", peerId,
            "avatarUrl", "/api/avatar/" + peerId,
            "thumbnailUrl", "/api/avatar/" + peerId + "/thumb"
        );
    }

    /**
     * Получить аватар пира.
     */
    @GetMapping("/{peerId}")
    public ResponseEntity<byte[]> getAvatar(@PathVariable String peerId) {
        UserAvatar avatar = avatarService.getAvatar(peerId);
        if (avatar == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path path = avatarService.getAvatarPath(avatar.getFilePath());
            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }

            byte[] data = Files.readAllBytes(path);
            String contentType = avatar.getFilePath().endsWith(".png") ? "image/png" : "image/jpeg";

            return ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(contentType))
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Получить thumbnail аватара.
     */
    @GetMapping("/{peerId}/thumb")
    public ResponseEntity<byte[]> getAvatarThumb(@PathVariable String peerId) {
        UserAvatar avatar = avatarService.getAvatar(peerId);
        if (avatar == null || avatar.getThumbnailPath() == null) {
            return ResponseEntity.notFound().build();
        }

        try {
            Path path = avatarService.getThumbPath(avatar.getThumbnailPath());
            if (!Files.exists(path)) {
                return ResponseEntity.notFound().build();
            }

            byte[] data = Files.readAllBytes(path);
            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(data);
        } catch (IOException e) {
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Удалить аватар.
     */
    @DeleteMapping
    public Map<String, Object> deleteAvatar(@RequestParam String peerId) {
        avatarService.deleteAvatar(peerId);
        return Map.of("status", "avatar_deleted");
    }
}
